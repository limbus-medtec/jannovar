package de.charite.compbio.jannovar.cmd.hgvs_to_vcf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.collect.Lists;

import de.charite.compbio.jannovar.JannovarException;
import de.charite.compbio.jannovar.UncheckedJannovarException;
import de.charite.compbio.jannovar.cmd.CommandLineParsingException;
import de.charite.compbio.jannovar.cmd.JannovarAnnotationCommand;
import de.charite.compbio.jannovar.hgvs.HGVSVariant;
import de.charite.compbio.jannovar.hgvs.bridge.CannotTranslateHGVSVariant;
import de.charite.compbio.jannovar.hgvs.bridge.NucleotideChangeToGenomeVariantTranslator;
import de.charite.compbio.jannovar.hgvs.nts.variant.SingleAlleleNucleotideVariant;
import de.charite.compbio.jannovar.hgvs.parser.HGVSParser;
import de.charite.compbio.jannovar.hgvs.parser.HGVSParsingException;
import de.charite.compbio.jannovar.reference.GenomeVariant;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFContigHeaderLine;
import htsjdk.variant.vcf.VCFFilterHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFSimpleHeaderLine;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Project transcript to chromosomal changes
 * 
 * @author <a href="mailto:manuel.holtgrewe@bihealth.de">Manuel Holtgrewe</a>
 */
public class ProjectTranscriptToChromosome extends JannovarAnnotationCommand {

	/** Configuration */
	private ProjectTranscriptToChromosomeOptions options;

	/** FAI-indexed FASTA file to use */
	IndexedFastaSequenceFile fasta;

	/** Translation of variants */
	NucleotideChangeToGenomeVariantTranslator translator;

	public ProjectTranscriptToChromosome(String argv[], Namespace args) throws CommandLineParsingException {
		this.options = new ProjectTranscriptToChromosomeOptions();
		this.options.setFromArgs(args);
	}

	@Override
	public void run() throws JannovarException {
		System.err.println("Options");
		System.err.println(options.toString());
		System.err.println("Loading database file...");
		deserializeTranscriptDefinitionFile(options.getDatabaseFilePath());
		System.err.println("Loading FASTA index...");
		loadFASTAIndex();
		System.err.println("Opening output VCF file...");
		try (VariantContextWriter writer = openOutputFile()) {
			processFile(writer);
		}
	}

	private VariantContextWriter openOutputFile() {
		VariantContextWriterBuilder builder = new VariantContextWriterBuilder()
				.setReferenceDictionary(fasta.getSequenceDictionary()).setOutputFile(options.getPathOutputVCF());
		if (options.getPathOutputVCF().endsWith(".gz") || options.getPathOutputVCF().endsWith(".bcf"))
			builder.setOption(Options.INDEX_ON_THE_FLY);
		else
			builder.unsetOption(Options.INDEX_ON_THE_FLY);
		VariantContextWriter writer = builder.build();

		VCFHeader header = new VCFHeader();
		int i = 0;
		for (SAMSequenceRecord record : fasta.getSequenceDictionary().getSequences()) {
			Map<String, String> mapping = new TreeMap<String, String>();
			mapping.put("ID", record.getSequenceName());
			mapping.put("length", Integer.toString(record.getSequenceLength()));
			header.addMetaDataLine(new VCFContigHeaderLine(mapping, i++));
		}

		header.addMetaDataLine(new VCFSimpleHeaderLine("ALT", "ERROR", "Error in conversion"));
		header.addMetaDataLine(new VCFFilterHeaderLine("PARSE_ERROR",
				"Problem in parsing original HGVS variant string, written out as variant at 1:g.1N>N"));
		header.addMetaDataLine(new VCFInfoHeaderLine("ERROR_MESSAGE", 1, VCFHeaderLineType.String, "Error message"));
		header.addMetaDataLine(new VCFInfoHeaderLine("ORIG_VAR", 1, VCFHeaderLineType.String,
				"Original HGVS variant string from input file to hgvs-to-vcf"));

		writer.writeHeader(header);

		return writer;
	}

	private void loadFASTAIndex() {
		try {
			this.fasta = new IndexedFastaSequenceFile(new File(options.getPathReferenceFASTA()));
		} catch (FileNotFoundException e) {
			throw new UncheckedJannovarException("Could not load FASTA index", e);
		}

		this.translator = new NucleotideChangeToGenomeVariantTranslator(jannovarData, fasta);
	}

	/**
	 * @return Variant context indicating error
	 */
	private VariantContext buildErrorVariantContext(String origString, String message) {
		Allele alleleRef = Allele.create("N", true);
		Allele alleleAlt = Allele.create("<ERROR>", false);
		return new VariantContextBuilder().loc("1", 1, 1).alleles(Lists.newArrayList(alleleRef, alleleAlt))
				.filter("PARSE_ERROR").attribute("ORIG_VAR", urlEncode(origString))
				.attribute("ERROR_MESSAGE", urlEncode(message)).make();
	}

	private String urlEncode(String s) {
		try {
			return URLEncoder.encode(s, "utf-8").replaceAll("=", "%3D");
		} catch (UnsupportedEncodingException e) {
			return s;
		}
	}

	private void processFile(VariantContextWriter writer) {
		try (BufferedReader br = new BufferedReader(new FileReader(new File(options.getPathInputText())))) {
			String line;
			while ((line = br.readLine()) != null) {
				// Read line
				String word = line.trim();

				// Parse variant
				HGVSVariant rawVar = null;
				try {
					HGVSParser parser = new HGVSParser();
					rawVar = parser.parseHGVSString(word);
					if (!(rawVar instanceof SingleAlleleNucleotideVariant)) {
						writer.add(buildErrorVariantContext(word, "More than one allele in nucleotide variant"));
						continue;
					}
				} catch (HGVSParsingException e) {
					writer.add(buildErrorVariantContext(word, e.getMessage()));
					continue;
				}

				// Convert from transcript to genome variant
				GenomeVariant genomeVar = translate((SingleAlleleNucleotideVariant) rawVar);
				if (genomeVar == null) {
					writer.add(buildErrorVariantContext(word, "Could not translate HGVS to genomic variant"));
					continue;
				}

				// Write variant to VCF file
				writeVariant(writer, genomeVar);
				System.err.println(word + " => " + rawVar + " => " + genomeVar);
			}
		} catch (FileNotFoundException e) {
			throw new UncheckedJannovarException("Problem opening file", e);
		} catch (IOException e) {
			throw new UncheckedJannovarException("Problem reading from file", e);
		}
	}

	private void writeVariant(VariantContextWriter writer, GenomeVariant genomeVar) {
		List<Allele> alleles = new ArrayList<Allele>();
		if (genomeVar.getRef().isEmpty() || genomeVar.getAlt().isEmpty()) {
			String left = fasta.getSubsequenceAt(genomeVar.getChrName(), genomeVar.getPos(), genomeVar.getPos() + 1)
					.getBaseString();
			alleles.add(Allele.create(left + genomeVar.getRef(), true));
			alleles.add(Allele.create(left + genomeVar.getAlt(), false));
		} else {
			alleles.add(Allele.create(genomeVar.getRef(), true));
			alleles.add(Allele.create(genomeVar.getAlt(), false));
		}

		VariantContextBuilder builder = new VariantContextBuilder();
		builder.chr(genomeVar.getChrName()).start(genomeVar.getPos() + 1)
				.computeEndFromAlleles(alleles, genomeVar.getPos() + 1).alleles(alleles);

		writer.add(builder.make());
	}

	private GenomeVariant translate(SingleAlleleNucleotideVariant rawVar) {
		try {
			return translator.translateNucleotideVariantToGenomeVariant((SingleAlleleNucleotideVariant) rawVar, true);
		} catch (CannotTranslateHGVSVariant e) {
			System.err.println("Could not translate variant " + rawVar + ": " + e.toString());
			return null;
		}
	}

}
