package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.CandidateResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pure, deterministic implementation of Reciprocal Rank Fusion (RRF).
 *
 * <p><strong>Formulation:</strong><br>
 * For a candidate result at 1-based rank \( r \ge 1 \):
 * \[
 * \text{RRF contribution} = \frac{1}{k + r}
 * \]
 * Total fused score for a chunk:
 * \[
 * \text{fusedScore} = w_{\text{semantic}} \cdot \text{semanticContribution} + w_{\text{keyword}} \cdot \text{keywordContribution}
 * \]
 * </p>
 *
 * <p><strong>Key Invariants:</strong>
 * <ul>
 *   <li>Ranks are strictly 1-based (\( r \in \{1, 2, \dots\} \)).</li>
 *   <li>RRF uses rank position, NOT raw similarity scores, preventing distortion across differing score scales.</li>
 *   <li>Chunks appearing in only one source list receive that source's contribution with zero contribution from the other.</li>
 *   <li>Chunks appearing in both lists combine both contributions.</li>
 *   <li>Deterministic tie-breaking: (1) fused score descending, (2) chunk ID ascending.</li>
 * </ul>
 * </p>
 */
@Component
public class ReciprocalRankFuser {

    /**
     * Represents a candidate chunk fused from semantic and keyword retrieval lists.
     *
     * @param chunkId      ID of the document chunk
     * @param documentId   ID of the parent document
     * @param fusedScore   Combined RRF score
     * @param semanticRank 1-based rank in semantic results, or null if not present
     * @param keywordRank  1-based rank in keyword results, or null if not present
     */
    public record FusedCandidate(
            Long chunkId,
            Long documentId,
            float fusedScore,
            Integer semanticRank,
            Integer keywordRank
    ) {}

    /**
     * Fuses semantic and keyword candidate lists into a single ranked list.
     *
     * @param semanticCandidates list of candidates retrieved from semantic search
     * @param keywordCandidates  list of candidates retrieved from keyword search
     * @param rrfK               RRF smoothing constant (e.g. 60)
     * @param semanticWeight     weight applied to semantic RRF contribution
     * @param keywordWeight      weight applied to keyword RRF contribution
     * @param topK               maximum number of final fused candidates to return
     * @return deterministic, fused candidate list sorted by score DESC, chunkId ASC
     */
    public List<FusedCandidate> fuse(
            List<CandidateResult> semanticCandidates,
            List<CandidateResult> keywordCandidates,
            int rrfK,
            double semanticWeight,
            double keywordWeight,
            int topK) {

        if (rrfK < 0) {
            throw new IllegalArgumentException("rrfK must be non-negative, but was: " + rrfK);
        }
        if (topK < 0) {
            throw new IllegalArgumentException("topK must be non-negative, but was: " + topK);
        }
        if (topK == 0) {
            return Collections.emptyList();
        }

        List<CandidateResult> safeSemantic = (semanticCandidates != null) ? semanticCandidates : Collections.emptyList();
        List<CandidateResult> safeKeyword = (keywordCandidates != null) ? keywordCandidates : Collections.emptyList();

        if (safeSemantic.isEmpty() && safeKeyword.isEmpty()) {
            return Collections.emptyList();
        }

        // Map to store earliest (best) rank and documentId per chunkId
        Map<Long, Integer> semanticRanks = new LinkedHashMap<>();
        Map<Long, Long> chunkDocumentMap = new HashMap<>();
        for (CandidateResult c : safeSemantic) {
            semanticRanks.putIfAbsent(c.chunkId(), c.retrievalRank());
            chunkDocumentMap.putIfAbsent(c.chunkId(), c.documentId());
        }

        Map<Long, Integer> keywordRanks = new LinkedHashMap<>();
        for (CandidateResult c : safeKeyword) {
            keywordRanks.putIfAbsent(c.chunkId(), c.retrievalRank());
            chunkDocumentMap.putIfAbsent(c.chunkId(), c.documentId());
        }

        // Collect all distinct chunk IDs across both sources
        Set<Long> allChunkIds = new LinkedHashSet<>(semanticRanks.keySet());
        allChunkIds.addAll(keywordRanks.keySet());

        List<FusedCandidate> fusedList = new ArrayList<>(allChunkIds.size());
        for (Long chunkId : allChunkIds) {
            Integer semRank = semanticRanks.get(chunkId);
            Integer kwRank = keywordRanks.get(chunkId);
            Long docId = chunkDocumentMap.get(chunkId);

            double semContribution = (semRank != null) ? (semanticWeight / (rrfK + semRank)) : 0.0;
            double kwContribution = (kwRank != null) ? (keywordWeight / (rrfK + kwRank)) : 0.0;
            float totalFusedScore = (float) (semContribution + kwContribution);

            fusedList.add(new FusedCandidate(
                    chunkId,
                    docId,
                    totalFusedScore,
                    semRank,
                    kwRank
            ));
        }

        // Deterministic sorting:
        // 1. Fused score descending
        // 2. Chunk ID ascending
        fusedList.sort((a, b) -> {
            int scoreCmp = Float.compare(b.fusedScore(), a.fusedScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            return Long.compare(a.chunkId(), b.chunkId());
        });

        // Retain only final topK
        if (fusedList.size() > topK) {
            return new ArrayList<>(fusedList.subList(0, topK));
        }

        return fusedList;
    }
}
