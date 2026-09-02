package com.enterprise.aiknowledge.repository;

import com.enterprise.aiknowledge.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link DocumentChunk} persistence and retrieval.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * Retrieves all chunks belonging to a document ordered sequentially by chunk index.
     *
     * @param documentId parent document primary key
     * @return list of ordered document chunks
     */
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    /**
     * Deletes all chunks associated with a document (useful during deletion and reprocessing).
     *
     * @param documentId parent document primary key
     */
    void deleteByDocumentId(Long documentId);

    /**
     * Checks if any chunks already exist for a given document.
     *
     * @param documentId parent document primary key
     * @return true if at least one chunk exists
     */
    boolean existsByDocumentId(Long documentId);

    /**
     * Counts the total number of chunks created for a document.
     *
     * @param documentId parent document primary key
     * @return count of chunks
     */
    long countByDocumentId(Long documentId);

    /**
     * Batch-retrieves document chunks along with their parent document and owner eagerly,
     * preventing N+1 queries during semantic search chunk retrieval.
     *
     * @param ids collection of chunk IDs
     * @return list of document chunks with initialized document and owner associations
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM DocumentChunk c JOIN FETCH c.document d JOIN FETCH d.owner WHERE c.id IN :ids"
    )
    List<DocumentChunk> findAllWithDocumentAndOwnerByIdIn(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
