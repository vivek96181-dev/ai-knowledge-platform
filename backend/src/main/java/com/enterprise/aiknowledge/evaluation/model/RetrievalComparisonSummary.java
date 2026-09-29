package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.Map;

/**
 * Summary comparing retrieval metrics between Baseline (Hybrid Search) and Experiment (Hybrid Search + Reranking).
 *
 * @param datasetName           name of evaluated dataset
 * @param totalQueries          number of evaluated queries
 * @param meanRecallAtKBaseline mean Recall@K for Hybrid Search baseline
 * @param meanRecallAtKReranked mean Recall@K for Hybrid + Reranking experiment
 * @param meanPrecisionAtKBaseline mean Precision@K for Hybrid Search baseline
 * @param meanPrecisionAtKReranked mean Precision@K for Hybrid + Reranking experiment
 * @param mrrBaseline           Mean Reciprocal Rank for Hybrid Search baseline
 * @param mrrReranked           Mean Reciprocal Rank for Hybrid + Reranking experiment
 * @param formattedReport       Human-readable formatted ASCII comparison table
 */
public record RetrievalComparisonSummary(
        String datasetName,
        int totalQueries,
        Map<Integer, Double> meanRecallAtKBaseline,
        Map<Integer, Double> meanRecallAtKReranked,
        Map<Integer, Double> meanPrecisionAtKBaseline,
        Map<Integer, Double> meanPrecisionAtKReranked,
        double mrrBaseline,
        double mrrReranked,
        String formattedReport
) {
    public RetrievalComparisonSummary {
        meanRecallAtKBaseline = (meanRecallAtKBaseline != null) ? Collections.unmodifiableMap(meanRecallAtKBaseline) : Collections.emptyMap();
        meanRecallAtKReranked = (meanRecallAtKReranked != null) ? Collections.unmodifiableMap(meanRecallAtKReranked) : Collections.emptyMap();
        meanPrecisionAtKBaseline = (meanPrecisionAtKBaseline != null) ? Collections.unmodifiableMap(meanPrecisionAtKBaseline) : Collections.emptyMap();
        meanPrecisionAtKReranked = (meanPrecisionAtKReranked != null) ? Collections.unmodifiableMap(meanPrecisionAtKReranked) : Collections.emptyMap();
    }
}
