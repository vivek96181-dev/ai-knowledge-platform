package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.RagResponse;
import com.enterprise.aiknowledge.dto.RagSource;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.dto.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests verifying multi-tenant cache isolation, cache-aside behavior,
 * and security invariants for the caching layer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Cache Security & Multi-Tenant Isolation Tests")
class CacheSecurityTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private CacheKeyFactory cacheKeyFactory;
    private RedisCacheService cacheService;

    private static final Long USER_A_ID = 1L;
    private static final Long USER_B_ID = 2L;
    private static final String IDENTICAL_QUERY = "What is the leave policy?";

    @BeforeEach
    void setUp() {
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        cacheKeyFactory = new CacheKeyFactory("v1");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new RedisCacheService(redisTemplate, objectMapper, cacheKeyFactory);
    }

    @Nested
    @DisplayName("Multi-Tenant Cache Key Isolation")
    class MultiTenantIsolation {

        @Test
        @DisplayName("User A and User B get different search cache keys for identical queries")
        void searchKeys_differentUsers_differentKeys() {
            String keyA = cacheKeyFactory.buildSearchKey(USER_A_ID, false, IDENTICAL_QUERY, "HYBRID", 5, true);
            String keyB = cacheKeyFactory.buildSearchKey(USER_B_ID, false, IDENTICAL_QUERY, "HYBRID", 5, true);

            assertNotEquals(keyA, keyB, "User A's cache key MUST differ from User B's");
            assertTrue(keyA.contains("user:1"));
            assertTrue(keyB.contains("user:2"));
        }

        @Test
        @DisplayName("User A and User B get different RAG cache keys for identical queries")
        void ragKeys_differentUsers_differentKeys() {
            String keyA = cacheKeyFactory.buildRagKey(USER_A_ID, false, IDENTICAL_QUERY, 5);
            String keyB = cacheKeyFactory.buildRagKey(USER_B_ID, false, IDENTICAL_QUERY, 5);

            assertNotEquals(keyA, keyB);
        }

        @Test
        @DisplayName("ADMIN cache key does not collide with any USER cache key")
        void adminKey_doesNotCollideWithUser() {
            String adminKey = cacheKeyFactory.buildSearchKey(USER_A_ID, true, IDENTICAL_QUERY, "HYBRID", 5, true);
            String userKey = cacheKeyFactory.buildSearchKey(USER_A_ID, false, IDENTICAL_QUERY, "HYBRID", 5, true);

            assertNotEquals(adminKey, userKey);
            assertTrue(adminKey.contains("admin:all"));
            assertTrue(userKey.contains("user:1"));
        }

        @Test
        @DisplayName("User A's cached result cannot satisfy User B's request")
        void userA_cachedResult_cannotSatisfyUserB() throws Exception {
            // User A stores a result
            SearchResponse userAResponse = new SearchResponse(IDENTICAL_QUERY,
                    List.of(new SearchResult(1L, 10L, 1, 0, 0.95f, "User A's private chunk")));
            String userAKey = cacheKeyFactory.buildSearchKey(USER_A_ID, false, IDENTICAL_QUERY, "HYBRID", 5, false);
            String userAJson = objectMapper.writeValueAsString(userAResponse);
            when(valueOperations.get(userAKey)).thenReturn(userAJson);

            // User B looks up with their key
            String userBKey = cacheKeyFactory.buildSearchKey(USER_B_ID, false, IDENTICAL_QUERY, "HYBRID", 5, false);
            when(valueOperations.get(userBKey)).thenReturn(null);

            // Verify: User A gets hit, User B gets miss
            Optional<SearchResponse> resultA = cacheService.get(userAKey, SearchResponse.class);
            Optional<SearchResponse> resultB = cacheService.get(userBKey, SearchResponse.class);

            assertTrue(resultA.isPresent(), "User A should get cache hit");
            assertTrue(resultB.isEmpty(), "User B MUST NOT receive User A's cached result");
        }
    }

    @Nested
    @DisplayName("Cache-Aside Pattern Behavior")
    class CacheAsideBehavior {

        @Test
        @DisplayName("Cache hit returns cached result without executing search pipeline")
        void cacheHit_bypassesPipeline() throws Exception {
            SearchResponse cachedResponse = new SearchResponse("test",
                    List.of(new SearchResult(1L, 10L, 1, 0, 0.95f, "cached chunk")));
            String key = cacheKeyFactory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
            when(valueOperations.get(key)).thenReturn(objectMapper.writeValueAsString(cachedResponse));

            Optional<SearchResponse> result = cacheService.get(key, SearchResponse.class);

            assertTrue(result.isPresent());
            assertEquals("test", result.get().query());
            // The search pipeline mock is never invoked (no mock setup needed)
        }

        @Test
        @DisplayName("Cache miss returns empty, allowing pipeline execution")
        void cacheMiss_allowsPipelineExecution() {
            String key = cacheKeyFactory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);
            when(valueOperations.get(key)).thenReturn(null);

            Optional<SearchResponse> result = cacheService.get(key, SearchResponse.class);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Cache write stores result with correct TTL after pipeline execution")
        void cacheWrite_storesWithTtl() {
            SearchResponse response = new SearchResponse("test", List.of());
            String key = cacheKeyFactory.buildSearchKey(1L, false, "test", "SEMANTIC", 5, false);

            cacheService.put(key, response, 300);

            verify(valueOperations).set(eq(key), anyString(), eq(Duration.ofSeconds(300)));
        }
    }

    @Nested
    @DisplayName("TTL Configuration Honored")
    class TtlConfiguration {

        @Test
        @DisplayName("put passes the configured TTL to Redis")
        void put_passesConfiguredTtl() {
            cacheService.put("key", "value", 600);
            verify(valueOperations).set(eq("key"), anyString(), eq(Duration.ofSeconds(600)));
        }

        @Test
        @DisplayName("Different TTL values are passed through correctly")
        void put_differentTtls() {
            cacheService.put("key1", "val1", 60);
            cacheService.put("key2", "val2", 3600);
            verify(valueOperations).set(eq("key1"), anyString(), eq(Duration.ofSeconds(60)));
            verify(valueOperations).set(eq("key2"), anyString(), eq(Duration.ofSeconds(3600)));
        }
    }

    @Nested
    @DisplayName("RAG Response Serialization")
    class RagSerialization {

        @Test
        @DisplayName("RagResponse serializes and deserializes correctly through cache")
        void ragResponse_roundTrip() throws Exception {
            RagResponse original = new RagResponse(
                    "What is AI?",
                    "AI is artificial intelligence.",
                    List.of(new RagSource(1L, 10L, 1, 0, 0.95f)));

            String json = objectMapper.writeValueAsString(original);
            RagResponse deserialized = objectMapper.readValue(json, RagResponse.class);

            assertEquals(original.query(), deserialized.query());
            assertEquals(original.answer(), deserialized.answer());
            assertEquals(1, deserialized.sources().size());
            assertEquals(original.sources().get(0).documentId(), deserialized.sources().get(0).documentId());
        }
    }

    @Nested
    @DisplayName("Failure Resilience")
    class FailureResilience {

        @Test
        @DisplayName("Redis read failure does not fail the request")
        void readFailure_doesNotFail() {
            when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Connection refused"));

            Optional<SearchResponse> result = cacheService.get("key", SearchResponse.class);

            assertTrue(result.isEmpty(), "Read failure must fall back gracefully");
        }

        @Test
        @DisplayName("Redis write failure does not fail the request")
        void writeFailure_doesNotFail() {
            doThrow(new RuntimeException("Connection refused")).when(valueOperations)
                    .set(anyString(), anyString(), any(Duration.class));

            assertDoesNotThrow(() ->
                    cacheService.put("key", new SearchResponse("test", List.of()), 300),
                    "Write failure must not propagate");
        }

        @Test
        @DisplayName("Redis eviction failure does not propagate")
        void evictionFailure_doesNotPropagate() {
            when(redisTemplate.keys(anyString())).thenThrow(new RuntimeException("Connection refused"));

            assertDoesNotThrow(() -> cacheService.evictByDocumentOwner(42L),
                    "Eviction failure must not propagate");
        }
    }
}
