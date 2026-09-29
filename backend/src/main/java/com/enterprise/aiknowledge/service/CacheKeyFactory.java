package com.enterprise.aiknowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Factory producing deterministic, tenant-safe cache keys for search and RAG results.
 *
 * <p><strong>Key Structure:</strong></p>
 * <ul>
 *   <li>Search: {@code search:{version}:user:{userId}:mode:{mode}:topK:{topK}:rerank:{rerank}:q:{sha256}}</li>
 *   <li>Search (ADMIN): {@code search:{version}:admin:all:mode:{mode}:topK:{topK}:rerank:{rerank}:q:{sha256}}</li>
 *   <li>RAG: {@code rag:{version}:user:{userId}:topK:{topK}:q:{sha256}}</li>
 *   <li>RAG (ADMIN): {@code rag:{version}:admin:all:topK:{topK}:q:{sha256}}</li>
 * </ul>
 *
 * <p><strong>Security Invariants:</strong></p>
 * <ul>
 *   <li>User identity is derived from the authenticated principal — never from the request body.</li>
 *   <li>ADMIN scope uses a distinct namespace ({@code admin:all}) that cannot collide with USER keys.</li>
 *   <li>Query is normalized (trim, collapse whitespace, lowercase) before SHA-256 hashing.</li>
 * </ul>
 *
 * <p><strong>Why SHA-256:</strong> Java {@code Object.hashCode()} is unsuitable for distributed
 * cache keys because it is JVM-specific, not cryptographically stable, and susceptible to collisions.
 * SHA-256 provides a fixed-length, deterministic, collision-resistant digest.</p>
 */
@Component
public class CacheKeyFactory {

    private final String keyVersion;

    public CacheKeyFactory(@Value("${cache.key-version:v1}") String keyVersion) {
        this.keyVersion = keyVersion;
    }

    /**
     * Builds a cache key for search results.
     *
     * @param userId  authenticated user's database ID (from JWT principal, not request body)
     * @param isAdmin whether the user has ROLE_ADMIN
     * @param query   raw query string from the request
     * @param mode    search mode (SEMANTIC, KEYWORD, HYBRID)
     * @param topK    resolved topK value
     * @param rerank  whether reranking is active
     * @return deterministic cache key string
     */
    public String buildSearchKey(Long userId, boolean isAdmin, String query,
                                  String mode, int topK, boolean rerank) {
        String scope = isAdmin ? "admin:all" : "user:" + userId;
        String normalizedMode = (mode != null) ? mode.toUpperCase() : "SEMANTIC";
        String queryHash = sha256(normalizeQuery(query));

        return String.format("search:%s:%s:mode:%s:topK:%d:rerank:%s:q:%s",
                keyVersion, scope, normalizedMode, topK, rerank, queryHash);
    }

    /**
     * Builds a cache key for RAG results.
     *
     * @param userId  authenticated user's database ID
     * @param isAdmin whether the user has ROLE_ADMIN
     * @param query   raw query string from the request
     * @param topK    resolved topK value
     * @return deterministic cache key string
     */
    public String buildRagKey(Long userId, boolean isAdmin, String query, int topK) {
        String scope = isAdmin ? "admin:all" : "user:" + userId;
        String queryHash = sha256(normalizeQuery(query));

        return String.format("rag:%s:%s:topK:%d:q:%s",
                keyVersion, scope, topK, queryHash);
    }

    /**
     * Builds a Redis key pattern matching all search entries for a specific user.
     * Used for targeted cache invalidation when a user's documents change.
     */
    public String buildUserSearchPattern(Long userId) {
        return String.format("search:%s:user:%d:*", keyVersion, userId);
    }

    /**
     * Builds a Redis key pattern matching all RAG entries for a specific user.
     */
    public String buildUserRagPattern(Long userId) {
        return String.format("rag:%s:user:%d:*", keyVersion, userId);
    }

    /**
     * Builds a Redis key pattern matching all ADMIN search entries.
     * ADMIN results span all users' documents, so any document change must invalidate ADMIN cache.
     */
    public String buildAdminSearchPattern() {
        return String.format("search:%s:admin:all:*", keyVersion);
    }

    /**
     * Builds a Redis key pattern matching all ADMIN RAG entries.
     */
    public String buildAdminRagPattern() {
        return String.format("rag:%s:admin:all:*", keyVersion);
    }

    /**
     * Returns the current key version (useful for diagnostics and testing).
     */
    public String getKeyVersion() {
        return keyVersion;
    }

    /**
     * Normalizes a query string for consistent hashing.
     * <ul>
     *   <li>Trims leading/trailing whitespace</li>
     *   <li>Collapses multiple whitespace characters into a single space</li>
     *   <li>Converts to lowercase</li>
     * </ul>
     *
     * <p>This normalization ensures that queries like
     * {@code "  What  is   AI? "} and {@code "what is ai?"} produce the same cache key.</p>
     */
    static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Computes SHA-256 hex digest of the input string.
     */
    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java specification — this cannot happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
