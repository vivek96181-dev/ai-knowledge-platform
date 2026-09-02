package com.enterprise.aiknowledge.repository;

import com.enterprise.aiknowledge.model.DocumentChunkEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DocumentChunkEmbedding} metadata persistence and querying.
 */
@Repository
public interface DocumentChunkEmbeddingRepository extends JpaRepository<DocumentChunkEmbedding, Long> {

    /**
     * Finds embedding metadata associated with a specific document chunk.
     *
     * @param chunkId document chunk ID
     * @return optional containing embedding metadata if found
     */
    Optional<DocumentChunkEmbedding> findByDocumentChunkId(Long chunkId);

    /**
     * Checks if embedding metadata exists for a given document chunk.
     *
     * @param chunkId document chunk ID
     * @return true if exists
     */
    boolean existsByDocumentChunkId(Long chunkId);

    /**
     * Deletes embedding metadata for a single document chunk.
     *
     * @param chunkId document chunk ID
     */
    void deleteByDocumentChunkId(Long chunkId);

    /**
     * Finds all embedding metadata records belonging to chunks of a specific document.
     *
     * @param documentId parent document ID
     * @return list of embedding metadata records
     */
    List<DocumentChunkEmbedding> findByDocumentChunkDocumentId(Long documentId);

    /**
     * Deletes all embedding metadata records for chunks belonging to a specific document.
     *
     * @param documentId parent document ID
     */
    void deleteByDocumentChunkDocumentId(Long documentId);
}
