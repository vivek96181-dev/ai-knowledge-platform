package com.enterprise.aiknowledge.dto;

/**
 * Encapsulates a candidate chunk and its calculated query-to-chunk reranker relevance score.
 *
 * @param candidate   Underlying candidate chunk
 * @param rerankScore Relevance score assigned by the reranker (e.g. [0.0, 1.0])
 */
public record RerankedCandidate(
        RerankCandidate candidate,
        float rerankScore
) {}
