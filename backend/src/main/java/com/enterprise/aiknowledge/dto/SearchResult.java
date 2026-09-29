package com.enterprise.aiknowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Individual ranked search match returned in {@link SearchResponse}.
 *
 * <p><strong>Security Note:</strong> Internal filesystem paths, embedding vectors,
 * and internal database implementation details are excluded.</p>
 *
 * @param documentId  ID of the parent document
 * @param chunkId     ID of the matched document chunk
 * @param pageNumber  Source page number in the original PDF
 * @param chunkIndex  Sequential chunk index within the document
 * @param score       Retrieval/fusion score (Cosine similarity or RRF fused score)
 * @param text        Actual text content of the chunk retrieved from PostgreSQL
 * @param rerankScore Query-to-chunk relevance score from reranking (null if un-reranked)
 */
public record SearchResult(
        Long documentId,
        Long chunkId,
        int pageNumber,
        int chunkIndex,
        float score,
        String text,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Float rerankScore
) {
    /**
     * Backward-compatible constructor for un-reranked search results.
     */
    public SearchResult(Long documentId, Long chunkId, int pageNumber, int chunkIndex, float score, String text) {
        this(documentId, chunkId, pageNumber, chunkIndex, score, text, null);
    }
}
