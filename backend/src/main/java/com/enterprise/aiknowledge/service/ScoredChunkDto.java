package com.enterprise.aiknowledge.service;

/**
 * Domain DTO representing a scored vector search match from the vector store.
 *
 * <p>Isolates vector database internals (such as Qdrant Protobuf types) from the
 * rest of the application.</p>
 *
 * @param chunkId    ID of the matched {@link com.enterprise.aiknowledge.model.DocumentChunk}
 * @param documentId ID of the parent {@link com.enterprise.aiknowledge.model.Document}
 * @param pageNumber 1-based page number where the chunk text originated
 * @param chunkIndex 0-based sequential chunk index within the document
 * @param ownerId    ID of the user who owns the parent document
 * @param score      Cosine similarity relevance score
 */
public record ScoredChunkDto(
        Long chunkId,
        Long documentId,
        int pageNumber,
        int chunkIndex,
        Long ownerId,
        float score
) {}
