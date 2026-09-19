package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.Map;

/**
 * Metric results for the retrieval stage of a single evaluation query.
 *
 * @param recallAtK              map of K to Recall@K score (0.0 to 1.0)
 * @param precisionAtK           map of K to Precision@K score (0.0 to 1.0)
 * @param reciprocalRank         1 / (first relevant rank), or 0.0 if not found
 * @param firstRelevantRank      1-indexed rank of first relevant chunk, or null if none
 * @param retrievedCount         total number of chunks returned
 * @param relevantRetrievedCount number of expected relevant chunks retrieved
 * @param expectedCount          total expected relevant chunks
 */
public record RetrievalMetrics(
        Map<Integer, Double> recallAtK,
        Map<Integer, Double> precisionAtK,
        double reciprocalRank,
        Integer firstRelevantRank,
        int retrievedCount,
        int relevantRetrievedCount,
        int expectedCount
) {
    public RetrievalMetrics {
        recallAtK = (recallAtK != null) ? Collections.unmodifiableMap(recallAtK) : Collections.emptyMap();
        precisionAtK = (precisionAtK != null) ? Collections.unmodifiableMap(precisionAtK) : Collections.emptyMap();
    }
}
