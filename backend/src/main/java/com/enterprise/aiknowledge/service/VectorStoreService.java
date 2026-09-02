package com.enterprise.aiknowledge.service;

import java.util.List;

/**
 * Service interface defining vector store operations (e.g. Qdrant).
 *
 * <p><strong>Architectural Rationale:</strong><br>
 * Decouples the ingestion pipeline and Kafka consumer from concrete vector database implementations.
 * Enables switching vector databases or providing mocks for tests without changing ingestion logic.</p>
 */
public interface VectorStoreService {

    /**
     * Idempotently verifies that the target collection exists.
     * If the collection does not exist, it creates it with configured dimensions and distance metric.
     * If it already exists, it validates compatibility (dimension and distance metric).
     */
    void ensureCollectionExists();

    /**
     * Upserts a batch of chunk vectors and their metadata payloads into the vector store.
     * Uses deterministic point IDs (derived from chunkId) to guarantee idempotency.
     *
     * @param chunkVectors list of chunk vector DTOs to index
     */
    void upsertChunkVectors(List<ChunkVectorDto> chunkVectors);

    /**
     * Deletes all vector points belonging to a specific document.
     *
     * @param documentId ID of the document whose chunk vectors should be deleted
     */
    void deleteVectorsByDocumentId(Long documentId);

    /**
     * Returns the name of the target vector collection.
     */
    String getCollectionName();

    /**
     * Returns the expected vector dimensionality (e.g., 768).
     */
    int getVectorDimensions();
}
