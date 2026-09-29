package com.enterprise.aiknowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheKeyFactory — Deterministic Tenant-Safe Cache Key Generation")
class CacheKeyFactoryTest {

    private CacheKeyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CacheKeyFactory("v1");
    }

    // ============================================================
    // Query Normalization
    // ============================================================

    @Test
    @DisplayName("normalizeQuery trims leading/trailing whitespace")
    void normalizeQuery_trimsWhitespace() {
        assertEquals("hello world", CacheKeyFactory.normalizeQuery("  hello world  "));
    }

    @Test
    @DisplayName("normalizeQuery collapses multiple whitespace into single space")
    void normalizeQuery_collapsesWhitespace() {
        assertEquals("what is ai?", CacheKeyFactory.normalizeQuery("what   is    ai?"));
    }

    @Test
    @DisplayName("normalizeQuery lowercases the query")
    void normalizeQuery_lowercases() {
        assertEquals("what is ai?", CacheKeyFactory.normalizeQuery("What Is AI?"));
    }

    @Test
    @DisplayName("normalizeQuery handles null")
    void normalizeQuery_handlesNull() {
        assertEquals("", CacheKeyFactory.normalizeQuery(null));
    }

    @Test
    @DisplayName("normalizeQuery preserves semantic equivalence")
    void normalizeQuery_semanticEquivalence() {
        String a = CacheKeyFactory.normalizeQuery("  What  is   AI? ");
        String b = CacheKeyFactory.normalizeQuery("what is ai?");
        assertEquals(a, b);
    }

    // ============================================================
    // SHA-256 Hashing
    // ============================================================

    @Test
    @DisplayName("sha256 produces deterministic 64-character hex output")
    void sha256_deterministic() {
        String hash = CacheKeyFactory.sha256("hello");
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hex should be 64 characters");
        // Same input always produces same output
        assertEquals(hash, CacheKeyFactory.sha256("hello"));
    }

    @Test
    @DisplayName("sha256 produces different hashes for different inputs")
    void sha256_differentInputs() {
        assertNotEquals(CacheKeyFactory.sha256("hello"), CacheKeyFactory.sha256("world"));
    }

    @Test
    @DisplayName("sha256 matches known reference hash for 'hello'")
    void sha256_knownHash() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CacheKeyFactory.sha256("hello"));
    }

    // ============================================================
    // Search Key Structure
    // ============================================================

    @Test
    @DisplayName("buildSearchKey produces key containing version, user scope, mode, topK, rerank, and hash")
    void buildSearchKey_structure() {
        String key = factory.buildSearchKey(42L, false, "test query", "HYBRID", 5, true);
        assertTrue(key.startsWith("search:v1:user:42:mode:HYBRID:topK:5:rerank:true:q:"));
        assertTrue(key.length() > 60, "Key should include SHA-256 hash suffix");
    }

    @Test
    @DisplayName("buildSearchKey uses ADMIN scope for admin users")
    void buildSearchKey_adminScope() {
        String key = factory.buildSearchKey(42L, true, "test", "SEMANTIC", 5, false);
        assertTrue(key.contains("admin:all"));
        assertFalse(key.contains("user:42"));
    }

    @Test
    @DisplayName("Different users produce different search keys for same query")
    void buildSearchKey_differentUsers() {
        String keyA = factory.buildSearchKey(1L, false, "what is AI?", "SEMANTIC", 5, false);
        String keyB = factory.buildSearchKey(2L, false, "what is AI?", "SEMANTIC", 5, false);
        assertNotEquals(keyA, keyB, "User A and User B must have different cache keys");
    }

    @Test
    @DisplayName("Same user, same query produces identical keys (deterministic)")
    void buildSearchKey_deterministic() {
        String key1 = factory.buildSearchKey(1L, false, "what is AI?", "HYBRID", 5, true);
        String key2 = factory.buildSearchKey(1L, false, "what is AI?", "HYBRID", 5, true);
        assertEquals(key1, key2);
    }

    @Test
    @DisplayName("Different search modes produce different keys")
    void buildSearchKey_differentModes() {
        String semantic = factory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        String hybrid = factory.buildSearchKey(1L, false, "test", "HYBRID", 5, false);
        String keyword = factory.buildSearchKey(1L, false, "test", "KEYWORD", 5, false);
        assertNotEquals(semantic, hybrid);
        assertNotEquals(semantic, keyword);
        assertNotEquals(hybrid, keyword);
    }

    @Test
    @DisplayName("Different topK values produce different keys")
    void buildSearchKey_differentTopK() {
        String k3 = factory.buildSearchKey(1L, false, "test", "SEMANTIC", 3, false);
        String k5 = factory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        assertNotEquals(k3, k5);
    }

    @Test
    @DisplayName("Rerank flag changes the key")
    void buildSearchKey_rerankFlag() {
        String noRerank = factory.buildSearchKey(1L, false, "test", "HYBRID", 5, false);
        String withRerank = factory.buildSearchKey(1L, false, "test", "HYBRID", 5, true);
        assertNotEquals(noRerank, withRerank);
    }

    @Test
    @DisplayName("ADMIN namespace does not collide with any USER namespace")
    void buildSearchKey_adminDoesNotCollideWithUser() {
        String adminKey = factory.buildSearchKey(1L, true, "test", "SEMANTIC", 5, false);
        String userKey = factory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        assertNotEquals(adminKey, userKey,
                "ADMIN cache key must not collide with USER cache key for same user ID");
    }

    @Test
    @DisplayName("Null mode defaults to SEMANTIC in key")
    void buildSearchKey_nullModeDefaultsSemantic() {
        String key = factory.buildSearchKey(1L, false, "test", null, 5, false);
        assertTrue(key.contains("mode:SEMANTIC"));
    }

    // ============================================================
    // RAG Key Structure
    // ============================================================

    @Test
    @DisplayName("buildRagKey produces key containing rag prefix, version, user scope, topK, and hash")
    void buildRagKey_structure() {
        String key = factory.buildRagKey(42L, false, "test query", 5);
        assertTrue(key.startsWith("rag:v1:user:42:topK:5:q:"));
    }

    @Test
    @DisplayName("Different users produce different RAG keys for same query")
    void buildRagKey_differentUsers() {
        String keyA = factory.buildRagKey(1L, false, "what is AI?", 5);
        String keyB = factory.buildRagKey(2L, false, "what is AI?", 5);
        assertNotEquals(keyA, keyB);
    }

    @Test
    @DisplayName("buildRagKey ADMIN scope is distinct from USER scope")
    void buildRagKey_adminScope() {
        String adminKey = factory.buildRagKey(1L, true, "test", 5);
        String userKey = factory.buildRagKey(1L, false, "test", 5);
        assertTrue(adminKey.contains("admin:all"));
        assertNotEquals(adminKey, userKey);
    }

    // ============================================================
    // Invalidation Pattern Keys
    // ============================================================

    @Test
    @DisplayName("buildUserSearchPattern produces wildcard pattern for user")
    void buildUserSearchPattern_structure() {
        String pattern = factory.buildUserSearchPattern(42L);
        assertEquals("search:v1:user:42:*", pattern);
    }

    @Test
    @DisplayName("buildUserRagPattern produces wildcard pattern for user")
    void buildUserRagPattern_structure() {
        String pattern = factory.buildUserRagPattern(42L);
        assertEquals("rag:v1:user:42:*", pattern);
    }

    @Test
    @DisplayName("buildAdminSearchPattern produces wildcard pattern for admin")
    void buildAdminSearchPattern_structure() {
        assertEquals("search:v1:admin:all:*", factory.buildAdminSearchPattern());
    }

    @Test
    @DisplayName("buildAdminRagPattern produces wildcard pattern for admin")
    void buildAdminRagPattern_structure() {
        assertEquals("rag:v1:admin:all:*", factory.buildAdminRagPattern());
    }

    // ============================================================
    // Key Versioning
    // ============================================================

    @Test
    @DisplayName("Key version is embedded in all key types")
    void keyVersion_embedded() {
        CacheKeyFactory v2 = new CacheKeyFactory("v2");
        String searchKey = v2.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        String ragKey = v2.buildRagKey(1L, false, "test", 5);
        assertTrue(searchKey.contains(":v2:"));
        assertTrue(ragKey.contains(":v2:"));
    }

    @Test
    @DisplayName("Different key versions produce different keys for same query")
    void keyVersion_differentVersionsDifferentKeys() {
        CacheKeyFactory v1 = new CacheKeyFactory("v1");
        CacheKeyFactory v2 = new CacheKeyFactory("v2");
        String key1 = v1.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        String key2 = v2.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("getKeyVersion returns configured version")
    void getKeyVersion_returnsConfigured() {
        assertEquals("v1", factory.getKeyVersion());
    }
}
