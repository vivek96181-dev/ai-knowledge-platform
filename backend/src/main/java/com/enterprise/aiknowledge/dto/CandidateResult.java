package com.enterprise.aiknowledge.dto;

/**
 * Internal representation of a candidate search match from an individual retrieval subsystem
 * (Semantic or Keyword) prior to Reciprocal Rank Fusion (RRF) and database hydration.
 *
 * @param chunkId       ID of the matched document chunk
 * @param documentId    ID of the parent document
 * @param score         Raw relevance score from the retrieval source (cosine similarity or FTS ts_rank_cd)
 * @param retrievalRank 1-based rank position within this source's retrieved candidates (1, 2, 3, ...)
 * @param sourceType    Subsystem that retrieved this candidate (SEMANTIC or KEYWORD)
 */
public record CandidateResult(
        Long chunkId,
        Long documentId,
        float score,
        int retrievalRank,
        RetrievalSourceType sourceType
) {
    public CandidateResult {
        if (retrievalRank < 1) {
            throw new IllegalArgumentException("retrievalRank must be 1-based (>= 1), but was: " + retrievalRank);
        }
    }
}
