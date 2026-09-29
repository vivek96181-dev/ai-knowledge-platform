package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.CandidateResult;
import com.enterprise.aiknowledge.dto.RetrievalSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Pure unit tests for {@link ReciprocalRankFuser} covering RRF formula calculations,
 * rank-1 indexing, configurable k, custom weights, overlap fusion, deterministic tie-breaking,
 * top-K truncation, duplicate chunk handling, and empty set edge cases.
 */
class ReciprocalRankFuserTest {

    private ReciprocalRankFuser fuser;

    @BeforeEach
    void setUp() {
        fuser = new ReciprocalRankFuser();
    }

    private CandidateResult semantic(Long chunkId, Long docId, int rank) {
        return new CandidateResult(chunkId, docId, 0.9f, rank, RetrievalSourceType.SEMANTIC);
    }

    private CandidateResult keyword(Long chunkId, Long docId, int rank) {
        return new CandidateResult(chunkId, docId, 0.5f, rank, RetrievalSourceType.KEYWORD);
    }

    // =========================================================================
    // 1. Pure Metric & Exact Formula Tests
    // =========================================================================

    @Test
    @DisplayName("RRF calculation matches mathematical specification: A=1/(k+1), B=1/(k+2)+1/(k+1), C=1/(k+2)")
    void mathematicalFormulaVerification() {
        int k = 60;
        double wSem = 1.0;
        double wKw = 1.0;

        // Semantic: A (rank 1), B (rank 2)
        List<CandidateResult> sem = List.of(
                semantic(1L, 10L, 1),
                semantic(2L, 10L, 2)
        );

        // Keyword: B (rank 1), C (rank 2)
        List<CandidateResult> kw = List.of(
                keyword(2L, 10L, 1),
                keyword(3L, 10L, 2)
        );

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, k, wSem, wKw, 5);

        // Expected mathematical scores:
        // B (chunk 2): 1/(60+2) + 1/(60+1) = 1/62 + 1/61
        float expectedScoreB = (float) ((1.0 / 62.0) + (1.0 / 61.0));
        // A (chunk 1): 1/(60+1) = 1/61
        float expectedScoreA = (float) (1.0 / 61.0);
        // C (chunk 3): 1/(60+2) = 1/62
        float expectedScoreC = (float) (1.0 / 62.0);

        assertThat(fused).hasSize(3);

        // 1st: B
        assertThat(fused.get(0).chunkId()).isEqualTo(2L);
        assertThat(fused.get(0).fusedScore()).isCloseTo(expectedScoreB, offset(1e-6f));
        assertThat(fused.get(0).semanticRank()).isEqualTo(2);
        assertThat(fused.get(0).keywordRank()).isEqualTo(1);

        // 2nd: A
        assertThat(fused.get(1).chunkId()).isEqualTo(1L);
        assertThat(fused.get(1).fusedScore()).isCloseTo(expectedScoreA, offset(1e-6f));
        assertThat(fused.get(1).semanticRank()).isEqualTo(1);
        assertThat(fused.get(1).keywordRank()).isNull();

        // 3rd: C
        assertThat(fused.get(2).chunkId()).isEqualTo(3L);
        assertThat(fused.get(2).fusedScore()).isCloseTo(expectedScoreC, offset(1e-6f));
        assertThat(fused.get(2).semanticRank()).isNull();
        assertThat(fused.get(2).keywordRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("RRF rank starts strictly at 1, yielding 1/(k+1) for rank 1")
    void rankStartsAtOne() {
        int k = 60;
        List<CandidateResult> sem = List.of(semantic(100L, 1L, 1));
        List<CandidateResult> kw = Collections.emptyList();

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, k, 1.0, 1.0, 5);

        assertThat(fused).hasSize(1);
        float expected = (float) (1.0 / (60 + 1));
        assertThat(fused.get(0).fusedScore()).isCloseTo(expected, offset(1e-6f));
    }

    @Test
    @DisplayName("Configurable RRF constant k correctly scales contribution")
    void configurableRrfK() {
        int kSmall = 10;
        List<CandidateResult> sem = List.of(semantic(100L, 1L, 1));

        List<ReciprocalRankFuser.FusedCandidate> fusedSmall = fuser.fuse(sem, Collections.emptyList(), kSmall, 1.0, 1.0, 5);
        float expectedSmall = (float) (1.0 / (10 + 1));
        assertThat(fusedSmall.get(0).fusedScore()).isCloseTo(expectedSmall, offset(1e-6f));

        int kLarge = 100;
        List<ReciprocalRankFuser.FusedCandidate> fusedLarge = fuser.fuse(sem, Collections.emptyList(), kLarge, 1.0, 1.0, 5);
        float expectedLarge = (float) (1.0 / (100 + 1));
        assertThat(fusedLarge.get(0).fusedScore()).isCloseTo(expectedLarge, offset(1e-6f));
    }

    @Test
    @DisplayName("Configurable semantic and keyword weights are properly applied")
    void configurableWeights() {
        int k = 60;
        double semanticWeight = 2.5;
        double keywordWeight = 0.5;

        List<CandidateResult> sem = List.of(semantic(1L, 10L, 1)); // 2.5 / 61
        List<CandidateResult> kw = List.of(keyword(2L, 10L, 1));  // 0.5 / 61

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, k, semanticWeight, keywordWeight, 5);

        float expectedScore1 = (float) (2.5 / 61.0);
        float expectedScore2 = (float) (0.5 / 61.0);

        assertThat(fused.get(0).chunkId()).isEqualTo(1L);
        assertThat(fused.get(0).fusedScore()).isCloseTo(expectedScore1, offset(1e-6f));

        assertThat(fused.get(1).chunkId()).isEqualTo(2L);
        assertThat(fused.get(1).fusedScore()).isCloseTo(expectedScore2, offset(1e-6f));
    }

    // =========================================================================
    // 2. Overlap, Disjoint, and Duplicate Scenarios
    // =========================================================================

    @Test
    @DisplayName("Chunk appearing in both lists accumulates contributions from both sources")
    void chunkInBothListsAccumulatesScores() {
        int k = 60;
        List<CandidateResult> sem = List.of(semantic(42L, 1L, 1));
        List<CandidateResult> kw = List.of(keyword(42L, 1L, 1));

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, k, 1.0, 1.0, 5);

        assertThat(fused).hasSize(1);
        float expected = (float) ((1.0 / 61.0) + (1.0 / 61.0));
        assertThat(fused.get(0).fusedScore()).isCloseTo(expected, offset(1e-6f));
        assertThat(fused.get(0).semanticRank()).isEqualTo(1);
        assertThat(fused.get(0).keywordRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("Chunk appearing only in semantic list receives only semantic contribution")
    void chunkOnlyInSemanticList() {
        List<CandidateResult> sem = List.of(semantic(10L, 1L, 3));
        List<CandidateResult> kw = Collections.emptyList();

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, 60, 1.0, 1.0, 5);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).fusedScore()).isCloseTo((float) (1.0 / 63.0), offset(1e-6f));
        assertThat(fused.get(0).semanticRank()).isEqualTo(3);
        assertThat(fused.get(0).keywordRank()).isNull();
    }

    @Test
    @DisplayName("Chunk appearing only in keyword list receives only keyword contribution")
    void chunkOnlyInKeywordList() {
        List<CandidateResult> sem = Collections.emptyList();
        List<CandidateResult> kw = List.of(keyword(20L, 1L, 4));

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, 60, 1.0, 1.0, 5);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).fusedScore()).isCloseTo((float) (1.0 / 64.0), offset(1e-6f));
        assertThat(fused.get(0).semanticRank()).isNull();
        assertThat(fused.get(0).keywordRank()).isEqualTo(4);
    }

    @Test
    @DisplayName("Duplicate chunk IDs in candidate list preserve the best (earliest) rank")
    void duplicateChunkIdPreservesBestRank() {
        List<CandidateResult> sem = List.of(
                semantic(10L, 1L, 1),
                semantic(10L, 1L, 5) // Duplicate at worse rank
        );

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, Collections.emptyList(), 60, 1.0, 1.0, 5);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).semanticRank()).isEqualTo(1);
        assertThat(fused.get(0).fusedScore()).isCloseTo((float) (1.0 / 61.0), offset(1e-6f));
    }

    // =========================================================================
    // 3. Deterministic Tie-Breaking & Top-K Cutoff
    // =========================================================================

    @Test
    @DisplayName("Deterministic tie-breaking: identical scores are sorted by chunk ID ascending")
    void deterministicTieBreakingByChunkIdAscending() {
        // Chunk 200 and Chunk 100 both appear at rank 1 in their respective single lists
        // Semantic: Chunk 200 (rank 1) -> score 1/61
        // Keyword: Chunk 100 (rank 1) -> score 1/61
        List<CandidateResult> sem = List.of(semantic(200L, 1L, 1));
        List<CandidateResult> kw = List.of(keyword(100L, 1L, 1));

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, kw, 60, 1.0, 1.0, 5);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).fusedScore()).isEqualTo(fused.get(1).fusedScore());
        // Chunk 100 must come before Chunk 200 (100 < 200)
        assertThat(fused.get(0).chunkId()).isEqualTo(100L);
        assertThat(fused.get(1).chunkId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("Top-K bounding limits the final output list to exactly topK items")
    void topKBounding() {
        List<CandidateResult> sem = List.of(
                semantic(1L, 1L, 1),
                semantic(2L, 1L, 2),
                semantic(3L, 1L, 3),
                semantic(4L, 1L, 4),
                semantic(5L, 1L, 5)
        );

        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, Collections.emptyList(), 60, 1.0, 1.0, 2);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).chunkId()).isEqualTo(1L);
        assertThat(fused.get(1).chunkId()).isEqualTo(2L);
    }

    // =========================================================================
    // 4. Edge Cases & Boundary Handling
    // =========================================================================

    @Test
    @DisplayName("Both candidate sets empty returns empty list")
    void bothCandidateSetsEmpty() {
        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(
                Collections.emptyList(), Collections.emptyList(), 60, 1.0, 1.0, 5);
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("Null candidate lists are safely handled as empty")
    void nullCandidateListsSafelyHandled() {
        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(null, null, 60, 1.0, 1.0, 5);
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("topK = 0 returns empty list")
    void topKZeroReturnsEmptyList() {
        List<CandidateResult> sem = List.of(semantic(1L, 1L, 1));
        List<ReciprocalRankFuser.FusedCandidate> fused = fuser.fuse(sem, Collections.emptyList(), 60, 1.0, 1.0, 0);
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("Negative topK throws IllegalArgumentException")
    void negativeTopKThrowsException() {
        assertThatThrownBy(() -> fuser.fuse(Collections.emptyList(), Collections.emptyList(), 60, 1.0, 1.0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be non-negative");
    }

    @Test
    @DisplayName("Negative rrfK throws IllegalArgumentException")
    void negativeRrfKThrowsException() {
        assertThatThrownBy(() -> fuser.fuse(Collections.emptyList(), Collections.emptyList(), -1, 1.0, 1.0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rrfK must be non-negative");
    }
}
