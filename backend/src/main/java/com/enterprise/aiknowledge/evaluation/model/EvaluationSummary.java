package com.enterprise.aiknowledge.evaluation.model;

import java.util.*;

/**
 * Aggregated metric summary across an entire evaluation dataset run.
 */
public record EvaluationSummary(
        String datasetName,
        int totalCases,
        Map<Integer, Double> meanRecallAtK,
        Map<Integer, Double> meanPrecisionAtK,
        double meanReciprocalRank,
        double meanAnswerRelevance,
        double meanFaithfulness,
        double unanswerableAccuracy,
        int securityViolations,
        List<CaseEvaluationResult> caseResults
) {
    public EvaluationSummary {
        meanRecallAtK = (meanRecallAtK != null) ? Collections.unmodifiableMap(meanRecallAtK) : Collections.emptyMap();
        meanPrecisionAtK = (meanPrecisionAtK != null) ? Collections.unmodifiableMap(meanPrecisionAtK) : Collections.emptyMap();
        caseResults = (caseResults != null) ? Collections.unmodifiableList(caseResults) : Collections.emptyList();
    }

    /**
     * Renders a human-readable summary block for logs or CLI inspection.
     */
    public String toFormattedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("=================================").append(System.lineSeparator());
        sb.append("RAG EVALUATION").append(System.lineSeparator());
        sb.append("Dataset: ").append(datasetName).append(System.lineSeparator());
        sb.append("Queries: ").append(totalCases).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("Retrieval").append(System.lineSeparator());

        // Sort K values for deterministic reporting
        List<Integer> sortedK = new ArrayList<>(meanRecallAtK.keySet());
        Collections.sort(sortedK);
        for (Integer k : sortedK) {
            sb.append(String.format(Locale.US, "Recall@%d:    %.4f%n", k, meanRecallAtK.get(k)));
        }

        List<Integer> sortedPrecK = new ArrayList<>(meanPrecisionAtK.keySet());
        Collections.sort(sortedPrecK);
        for (Integer k : sortedPrecK) {
            sb.append(String.format(Locale.US, "Precision@%d: %.4f%n", k, meanPrecisionAtK.get(k)));
        }

        sb.append(String.format(Locale.US, "MRR:         %.4f%n", meanReciprocalRank));
        sb.append(System.lineSeparator());
        sb.append("Generation").append(System.lineSeparator());
        sb.append(String.format(Locale.US, "Answer Relevance:      %.4f%n", meanAnswerRelevance));
        sb.append(String.format(Locale.US, "Faithfulness:          %.4f%n", meanFaithfulness));
        sb.append(String.format(Locale.US, "Unanswerable Accuracy: %.4f%n", unanswerableAccuracy));
        sb.append(String.format(Locale.US, "Security Violations:   %d%n", securityViolations));
        sb.append("=================================").append(System.lineSeparator());

        return sb.toString();
    }
}
