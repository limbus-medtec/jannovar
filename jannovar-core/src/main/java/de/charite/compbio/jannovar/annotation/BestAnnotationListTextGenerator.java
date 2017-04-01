package de.charite.compbio.jannovar.annotation;

import com.google.common.collect.ImmutableList;

import de.charite.compbio.jannovar.Immutable;

// TODO(holtgrewe): Remove

/**
 * Decorator for {@link VariantAnnotations} for generating variant annotation strings for the best variant
 *
 * @author <a href="mailto:manuel.holtgrewe@charite.de">Manuel Holtgrewe</a>
 * @author <a href="mailto:Peter.Robinson@jax.org">Peter N Robinson</a>
 */
@Deprecated
@Immutable
public final class BestAnnotationListTextGenerator extends VariantAnnotationsTextGenerator {

	public BestAnnotationListTextGenerator(VariantAnnotations annotations, int alleleID, int altCount) {
		super(annotations, alleleID, altCount);
	}

	@Override
	protected ImmutableList<Annotation> getAnnotations() {
		if (annotations.getAnnotations().isEmpty())
			return annotations.getAnnotations().subList(0, 0);
		else
			return annotations.getAnnotations().subList(0, 1);
	}
}
