package com.enterprise.aiknowledge.dto;

/**
 * Individual ranked search match returned in {@link SearchResponse}.
 *
 * <p><strong>Security Note:</strong> Internal filesystem paths, embedding vectors,
 * and internal database implementation details are excluded.</p>
 *
 * @param documentId ID of the parent document
 * @param chunkId    ID of the matched document chunk
 * @param pageNumber Source page number in the original PDF
 * @param chunkIndex Sequential chunk index within the document
 * @param score      Cosine similarity relevance score
 * @param text       Actual text content of the chunk retrieved from PostgreSQL
 */
public record SearchResult(
        Long documentId,
        Long chunkId,
        int pageNumber,
        int chunkIndex,
        float score,
        String text
) {}
