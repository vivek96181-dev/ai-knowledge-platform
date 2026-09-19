package com.enterprise.aiknowledge.dto;

/**
 * Structured source reference indicating the document chunk used to ground the RAG answer.
 *
 * <p><strong>Security Note:</strong> Internal filesystem paths, embedding vectors,
 * and internal database implementation details are excluded.</p>
 *
 * @param documentId ID of the parent document
 * @param chunkId    ID of the matched document chunk
 * @param pageNumber Source page number in the original PDF
 * @param chunkIndex Sequential chunk index within the document
 * @param score      Retrieval similarity score
 */
public record RagSource(
        Long documentId,
        Long chunkId,
        int pageNumber,
        int chunkIndex,
        float score
) {}
