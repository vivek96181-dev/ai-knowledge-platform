package com.enterprise.aiknowledge.service;

import java.util.Optional;

/**
 * Application-level caching abstraction for search and RAG results.
 *
 * <p>Hides the caching infrastructure (Redis, in-memory, etc.) behind a simple interface
 * so that cache-specific code does not spread across controllers and services.</p>
 *
 * <p><strong>Contract:</strong></p>
 * <ul>
 *   <li>{@link #get} returns {@link Optional#empty()} on miss or read failure — never throws.</li>
 *   <li>{@link #put} is fire-and-forget — write failures are logged but do not fail the request.</li>
 *   <li>{@link #evict} is best-effort — eviction failures are logged but do not propagate.</li>
 * </ul>
 */
public interface CacheService {

    /**
     * Retrieves a cached value by key.
     *
     * @param key  cache key
     * @param type expected value class (for deserialization)
     * @param <T>  value type
     * @return cached value if present and deserializable, or empty on miss/error
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Stores a value in cache with a time-to-live.
     *
     * @param key        cache key
     * @param value      value to cache (serialized as JSON)
     * @param ttlSeconds time-to-live in seconds
     */
    void put(String key, Object value, long ttlSeconds);

    /**
     * Evicts a single cache entry by exact key.
     *
     * @param key cache key to evict
     */
    void evict(String key);

    /**
     * Evicts all cache entries whose keys belong to a specific user or are in the ADMIN namespace.
     * Called when a document owned by the specified user changes (upload, delete, reprocess).
     *
     * <p>Also evicts ADMIN-scoped entries because ADMIN search spans all users' documents,
     * so any document change potentially affects ADMIN search results.</p>
     *
     * @param ownerUserId database ID of the document owner
     */
    void evictByDocumentOwner(Long ownerUserId);

    /**
     * Returns whether caching is enabled and operational.
     */
    boolean isEnabled();
}
