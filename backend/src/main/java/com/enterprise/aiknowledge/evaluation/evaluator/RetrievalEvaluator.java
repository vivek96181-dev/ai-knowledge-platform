package com.enterprise.aiknowledge.evaluation.evaluator;

import com.enterprise.aiknowledge.evaluation.model.RetrievalMetrics;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pure mathematical evaluator for retrieval quality metrics:
 * Recall@K, Precision@K, and Mean Reciprocal Rank (MRR).
 */
@Component
public class RetrievalEvaluator {

    public static final List<Integer> DEFAULT_K_VALUES = List.of(1, 3, 5);

    private final List<Integer> configuredKValues;

    public RetrievalEvaluator() {
        this(DEFAULT_K_VALUES);
    }

    public RetrievalEvaluator(List<Integer> kValues) {
        if (kValues == null || kValues.isEmpty()) {
            this.configuredKValues = DEFAULT_K_VALUES;
        } else {
            this.configuredKValues = List.copyOf(kValues);
        }
    }

    /**
     * Evaluates retrieval metrics for a single query.
     *
     * @param retrievedChunkIds ordered list of chunk IDs returned by retrieval (rank 1 at index 0)
     * @param expectedChunkIds  list of known ground-truth relevant chunk IDs
     * @return structured {@link RetrievalMetrics}
     */
    public RetrievalMetrics evaluate(List<Long> retrievedChunkIds, List<Long> expectedChunkIds) {
        return evaluate(retrievedChunkIds, expectedChunkIds, configuredKValues);
    }

    /**
     * Evaluates retrieval metrics using explicitly supplied K values.
     */
    public RetrievalMetrics evaluate(
            List<Long> retrievedChunkIds,
            List<Long> expectedChunkIds,
            List<Integer> kValues) {

        List<Long> safeRetrieved = (retrievedChunkIds != null) ? retrievedChunkIds : Collections.emptyList();
        Set<Long> expectedSet = (expectedChunkIds != null) ? new LinkedHashSet<>(expectedChunkIds) : Collections.emptySet();

        Map<Integer, Double> recallAtK = new LinkedHashMap<>();
        Map<Integer, Double> precisionAtK = new LinkedHashMap<>();

        List<Integer> safeKValues = (kValues != null && !kValues.isEmpty()) ? kValues : configuredKValues;

        for (int k : safeKValues) {
            recallAtK.put(k, calculateRecallAtK(safeRetrieved, expectedSet, k));
            precisionAtK.put(k, calculatePrecisionAtK(safeRetrieved, expectedSet, k));
        }

        double reciprocalRank = calculateReciprocalRank(safeRetrieved, expectedSet);
        Integer firstRank = findFirstRelevantRank(safeRetrieved, expectedSet);

        // Count total relevant chunks present in retrieved list
        Set<Long> uniqueRetrieved = new HashSet<>(safeRetrieved);
        int relevantRetrieved = 0;
        for (Long id : uniqueRetrieved) {
            if (expectedSet.contains(id)) {
                relevantRetrieved++;
            }
        }

        return new RetrievalMetrics(
                recallAtK,
                precisionAtK,
                reciprocalRank,
                firstRank,
                safeRetrieved.size(),
                relevantRetrieved,
                expectedSet.size()
        );
    }

    /**
     * Calculates Recall@K:
     * (number of expected relevant chunks retrieved in top K) / (total number of expected relevant chunks).
     */
    public double calculateRecallAtK(List<Long> retrieved, Set<Long> expectedSet, int k) {
        if (k <= 0 || expectedSet.isEmpty()) {
            return (expectedSet.isEmpty() && (retrieved == null || retrieved.isEmpty())) ? 1.0 : 0.0;
        }
        if (retrieved == null || retrieved.isEmpty()) {
            return 0.0;
        }

        int limit = Math.min(k, retrieved.size());
        Set<Long> topK = new LinkedHashSet<>(retrieved.subList(0, limit));

        long hits = topK.stream().filter(expectedSet::contains).count();
        return (double) hits / expectedSet.size();
    }

    /**
     * Calculates Precision@K:
     * (number of expected relevant chunks retrieved in top K) / K.
     * When fewer than K results are retrieved, the denominator adjusts to actual retrieved count
     * to avoid unfairly penalizing small result sets.
     */
    public double calculatePrecisionAtK(List<Long> retrieved, Set<Long> expectedSet, int k) {
        if (k <= 0 || retrieved == null || retrieved.isEmpty() || expectedSet.isEmpty()) {
            return 0.0;
        }

        int limit = Math.min(k, retrieved.size());
        Set<Long> topK = new LinkedHashSet<>(retrieved.subList(0, limit));

        long hits = topK.stream().filter(expectedSet::contains).count();

        // When fewer than K results were returned, use actual count as denominator
        int denominator = Math.min(k, Math.max(1, retrieved.size()));
        return (double) hits / denominator;
    }

    /**
     * Calculates Reciprocal Rank for a single query:
     * 1 / (1-based rank of the first relevant chunk found).
     * If no relevant chunk is retrieved, returns 0.0.
     */
    public double calculateReciprocalRank(List<Long> retrieved, Set<Long> expectedSet) {
        Integer firstRank = findFirstRelevantRank(retrieved, expectedSet);
        if (firstRank == null) {
            return 0.0;
        }
        return 1.0 / firstRank;
    }

    /**
     * Finds the 1-based rank of the first relevant chunk in the retrieved list.
     */
    public Integer findFirstRelevantRank(List<Long> retrieved, Set<Long> expectedSet) {
        if (retrieved == null || retrieved.isEmpty() || expectedSet == null || expectedSet.isEmpty()) {
            return null;
        }

        for (int i = 0; i < retrieved.size(); i++) {
            Long id = retrieved.get(i);
            if (expectedSet.contains(id)) {
                return i + 1; // 1-indexed rank
            }
        }
        return null;
    }

    public List<Integer> getConfiguredKValues() {
        return configuredKValues;
    }
}
