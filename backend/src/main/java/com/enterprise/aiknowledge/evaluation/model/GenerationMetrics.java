package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.List;

/**
 * Metric results for the generation stage of a single evaluation query.
 *
 * @param relevanceScore                score (0.0 to 1.0) measuring concept coverage
 * @param faithfulnessScore             score (0.0 to 1.0) measuring ground truth context containment
 * @param unanswerableHandledCorrectly  true if conservative behavior was properly exhibited
 * @param missingConcepts               concepts expected from ground truth that were absent
 * @param unsupportedTokens             sample non-stopword tokens in answer absent from retrieved context
 */
public record GenerationMetrics(
        double relevanceScore,
        double faithfulnessScore,
        boolean unanswerableHandledCorrectly,
        List<String> missingConcepts,
        List<String> unsupportedTokens
) {
    public GenerationMetrics {
        missingConcepts = (missingConcepts != null) ? Collections.unmodifiableList(missingConcepts) : Collections.emptyList();
        unsupportedTokens = (unsupportedTokens != null) ? Collections.unmodifiableList(unsupportedTokens) : Collections.emptyList();
    }
}
