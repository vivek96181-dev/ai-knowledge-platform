package com.enterprise.aiknowledge.repository;

/**
 * Spring Data JPA projection for native PostgreSQL full-text search results.
 */
public interface ChunkKeywordMatch {
    Long getChunkId();
    Long getDocumentId();
    Float getScore();
}
