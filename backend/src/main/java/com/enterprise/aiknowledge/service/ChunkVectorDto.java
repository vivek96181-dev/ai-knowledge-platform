package com.enterprise.aiknowledge.service;

import java.util.List;

/**
 * Data transfer object encapsulating a chunk's embedding vector and retrieval metadata payload
 * for storage into the vector database.
 *
 * @param chunkId        primary key of the DocumentChunk (used as deterministic point ID)
 * @param documentId     foreign key of the parent Document
 * @param pageNumber     1-indexed source page number from PDF
 * @param chunkIndex     0-indexed sequential chunk index within the document
 * @param ownerId        ID of the user who owns the document (for future RBAC filtering)
 * @param vector         768-dimensional float embedding vector
 */
public record ChunkVectorDto(
        Long chunkId,
        Long documentId,
        Integer pageNumber,
        Integer chunkIndex,
        Long ownerId,
        List<Float> vector
) {
    public ChunkVectorDto {
        if (chunkId == null) {
            throw new IllegalArgumentException("chunkId cannot be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("documentId cannot be null");
        }
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("vector cannot be null or empty");
        }
    }
}
