package com.enterprise.aiknowledge.dto;

/**
 * Lightweight candidate record carrying authorized chunk context to the reranker.
 *
 * <p><strong>Security Note:</strong> Strictly excludes sensitive internal credentials,
 * filesystem paths, DB metadata, and tokens.</p>
 *
 * @param chunkId    ID of the matched document chunk
 * @param documentId ID of the parent document
 * @param pageNumber Source page number in the original PDF
 * @param chunkIndex Sequential chunk index within the document
 * @param rrfScore   Fused reciprocal rank score from Hybrid Search
 * @param text       Text content of the chunk retrieved from PostgreSQL
 */
public record RerankCandidate(
        Long chunkId,
        Long documentId,
        int pageNumber,
        int chunkIndex,
        float rrfScore,
        String text
) {}
