package com.enterprise.aiknowledge.evaluation;

import com.enterprise.aiknowledge.evaluation.evaluator.RetrievalEvaluator;
import com.enterprise.aiknowledge.evaluation.model.RetrievalMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RetrievalEvaluator Unit Tests")
class RetrievalEvaluatorTest {

    private RetrievalEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RetrievalEvaluator(List.of(1, 3, 5));
    }

    @Test
    @DisplayName("Recall@K calculates expected proportion retrieved in top K")
    void recallAtK_calculatesProportionCorrectly() {
        Set<Long> expected = Set.of(101L, 102L);

        // Top 1: only chunk 101 retrieved => Recall@1 = 1/2 = 0.5
        List<Long> retrieved = List.of(101L, 999L, 102L);
        assertEquals(0.5, evaluator.calculateRecallAtK(retrieved, expected, 1), 0.001);

        // Top 3: chunks 101 and 102 both in top 3 => Recall@3 = 2/2 = 1.0
        assertEquals(1.0, evaluator.calculateRecallAtK(retrieved, expected, 3), 0.001);

        // Top 5: Recall@5 = 1.0
        assertEquals(1.0, evaluator.calculateRecallAtK(retrieved, expected, 5), 0.001);
    }

    @Test
    @DisplayName("Precision@K calculates proportion of relevant items in top K")
    void precisionAtK_calculatesProportionCorrectly() {
        Set<Long> expected = Set.of(101L, 102L);
        List<Long> retrieved = List.of(101L, 999L, 102L, 888L, 777L);

        // Top 1: 1 hit in 1 => Precision@1 = 1.0
        assertEquals(1.0, evaluator.calculatePrecisionAtK(retrieved, expected, 1), 0.001);

        // Top 3: 2 hits (101, 102) out of 3 => Precision@3 = 2/3 = 0.6667
        assertEquals(2.0 / 3.0, evaluator.calculatePrecisionAtK(retrieved, expected, 3), 0.001);

        // Top 5: 2 hits out of 5 => Precision@5 = 2/5 = 0.40
        assertEquals(0.40, evaluator.calculatePrecisionAtK(retrieved, expected, 5), 0.001);
    }

    @Test
    @DisplayName("Precision@K adjusts denominator when fewer than K results are returned")
    void precisionAtK_handlesFewerThanKResults() {
        Set<Long> expected = Set.of(101L, 102L);
        // Only 2 results returned, both are relevant
        List<Long> retrieved = List.of(101L, 102L);

        // K=5, but only 2 returned. Denominator is 2 => Precision = 2/2 = 1.0
        assertEquals(1.0, evaluator.calculatePrecisionAtK(retrieved, expected, 5), 0.001);
    }

    @Test
    @DisplayName("MRR calculates reciprocal of first relevant chunk rank")
    void mrr_calculatesReciprocalRank() {
        Set<Long> expected = Set.of(101L);

        // Rank 1: RR = 1 / 1 = 1.0
        assertEquals(1.0, evaluator.calculateReciprocalRank(List.of(101L, 102L), expected), 0.001);

        // Rank 2: RR = 1 / 2 = 0.5
        assertEquals(0.5, evaluator.calculateReciprocalRank(List.of(999L, 101L), expected), 0.001);

        // Rank 3: RR = 1 / 3 = 0.3333
        assertEquals(1.0 / 3.0, evaluator.calculateReciprocalRank(List.of(999L, 888L, 101L), expected), 0.001);

        // Not found: RR = 0.0
        assertEquals(0.0, evaluator.calculateReciprocalRank(List.of(999L, 888L), expected), 0.001);
    }

    @Test
    @DisplayName("Handles empty retrieval and empty expected IDs cleanly")
    void handlesEmptyResultsAndExpected() {
        Set<Long> expected = Set.of(101L);

        // Empty retrieval
        assertEquals(0.0, evaluator.calculateRecallAtK(Collections.emptyList(), expected, 5), 0.001);
        assertEquals(0.0, evaluator.calculatePrecisionAtK(Collections.emptyList(), expected, 5), 0.001);
        assertEquals(0.0, evaluator.calculateReciprocalRank(Collections.emptyList(), expected), 0.001);
        assertNull(evaluator.findFirstRelevantRank(Collections.emptyList(), expected));

        // Null retrieval
        assertEquals(0.0, evaluator.calculateRecallAtK(null, expected, 5), 0.001);
        assertEquals(0.0, evaluator.calculatePrecisionAtK(null, expected, 5), 0.001);
        assertEquals(0.0, evaluator.calculateReciprocalRank(null, expected), 0.001);

        // Empty expected set
        assertEquals(0.0, evaluator.calculateRecallAtK(List.of(101L), Collections.emptySet(), 5), 0.001);
        assertEquals(0.0, evaluator.calculatePrecisionAtK(List.of(101L), Collections.emptySet(), 5), 0.001);
        assertEquals(0.0, evaluator.calculateReciprocalRank(List.of(101L), Collections.emptySet()), 0.001);
    }

    @Test
    @DisplayName("Handles invalid K values (K <= 0)")
    void handlesInvalidK() {
        Set<Long> expected = Set.of(101L);
        List<Long> retrieved = List.of(101L);

        assertEquals(0.0, evaluator.calculateRecallAtK(retrieved, expected, 0));
        assertEquals(0.0, evaluator.calculateRecallAtK(retrieved, expected, -1));

        assertEquals(0.0, evaluator.calculatePrecisionAtK(retrieved, expected, 0));
        assertEquals(0.0, evaluator.calculatePrecisionAtK(retrieved, expected, -1));
    }

    @Test
    @DisplayName("Handles duplicate retrieved IDs without double counting hits")
    void handlesDuplicateRetrievedIds() {
        Set<Long> expected = Set.of(101L);
        // Duplicate 101L in retrieved results
        List<Long> retrieved = List.of(101L, 101L, 101L);

        // Only counted once
        assertEquals(1.0, evaluator.calculateRecallAtK(retrieved, expected, 3), 0.001);
        assertEquals(1.0 / 3.0, evaluator.calculatePrecisionAtK(retrieved, expected, 3), 0.001);
    }

    @Test
    @DisplayName("evaluate method packages full RetrievalMetrics correctly")
    void evaluate_packagesMetricsCorrectly() {
        List<Long> retrieved = List.of(999L, 101L, 102L);
        List<Long> expected = List.of(101L, 102L);

        RetrievalMetrics metrics = evaluator.evaluate(retrieved, expected);

        assertNotNull(metrics);
        assertEquals(3, metrics.retrievedCount());
        assertEquals(2, metrics.relevantRetrievedCount());
        assertEquals(2, metrics.expectedCount());
        assertEquals(0.5, metrics.reciprocalRank(), 0.001);
        assertEquals(2, metrics.firstRelevantRank());

        assertEquals(0.0, metrics.recallAtK().get(1), 0.001);
        assertEquals(1.0, metrics.recallAtK().get(3), 0.001);
        assertEquals(1.0, metrics.recallAtK().get(5), 0.001);

        assertEquals(0.0, metrics.precisionAtK().get(1), 0.001);
        assertEquals(2.0 / 3.0, metrics.precisionAtK().get(3), 0.001);
        assertEquals(2.0 / 3.0, metrics.precisionAtK().get(5), 0.001);
    }
}
