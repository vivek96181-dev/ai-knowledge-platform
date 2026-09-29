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

    /**
     * Executes PostgreSQL full-text search filtered by document owner ID for multi-tenant isolation (USER role).
     *
     * @param query    natural-language search query evaluated via websearch_to_tsquery
     * @param language PostgreSQL FTS text search configuration (e.g. 'english')
     * @param ownerId  owner user ID to restrict search results to
     * @param limit    maximum number of ranked candidates to return
     * @return ranked keyword search matches ordered by ts_rank_cd DESC, chunkId ASC
     */
    @org.springframework.data.jpa.repository.Query(
            value = """
                    SELECT c.id AS chunkId,
                           c.document_id AS documentId,
                           ts_rank_cd(to_tsvector(cast(:language as regconfig), coalesce(c.text, '')), websearch_to_tsquery(cast(:language as regconfig), :query)) AS score
                    FROM document_chunks c
                    JOIN documents d ON c.document_id = d.id
                    WHERE to_tsvector(cast(:language as regconfig), coalesce(c.text, '')) @@ websearch_to_tsquery(cast(:language as regconfig), :query)
                      AND d.owner_id = :ownerId
                    ORDER BY score DESC, c.id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<ChunkKeywordMatch> searchKeywordByOwner(
            @org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("language") String language,
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId,
            @org.springframework.data.repository.query.Param("limit") int limit);

    /**
     * Executes cross-document PostgreSQL full-text search across all documents (ADMIN role).
     *
     * @param query    natural-language search query evaluated via websearch_to_tsquery
     * @param language PostgreSQL FTS text search configuration (e.g. 'english')
     * @param limit    maximum number of ranked candidates to return
     * @return ranked keyword search matches ordered by ts_rank_cd DESC, chunkId ASC
     */
    @org.springframework.data.jpa.repository.Query(
            value = """
                    SELECT c.id AS chunkId,
                           c.document_id AS documentId,
                           ts_rank_cd(to_tsvector(cast(:language as regconfig), coalesce(c.text, '')), websearch_to_tsquery(cast(:language as regconfig), :query)) AS score
                    FROM document_chunks c
                    JOIN documents d ON c.document_id = d.id
                    WHERE to_tsvector(cast(:language as regconfig), coalesce(c.text, '')) @@ websearch_to_tsquery(cast(:language as regconfig), :query)
                    ORDER BY score DESC, c.id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<ChunkKeywordMatch> searchKeywordAll(
            @org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("language") String language,
            @org.springframework.data.repository.query.Param("limit") int limit);
}
