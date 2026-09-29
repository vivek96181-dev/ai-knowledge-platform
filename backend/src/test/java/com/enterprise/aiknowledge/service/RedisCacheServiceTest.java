package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.dto.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCacheService — Redis-backed Cache Implementation Tests")
class RedisCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private CacheKeyFactory cacheKeyFactory;
    private RedisCacheService cacheService;

    private static final String TEST_KEY = "search:v1:user:1:mode:SEMANTIC:topK:5:rerank:false:q:abc123";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cacheKeyFactory = new CacheKeyFactory("v1");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new RedisCacheService(redisTemplate, objectMapper, cacheKeyFactory);
    }

    // ============================================================
    // Cache GET (Read)
    // ============================================================

    @Test
    @DisplayName("get returns cached value on cache hit")
    void get_cacheHit_returnsValue() throws Exception {
        SearchResponse expected = new SearchResponse("test",
                List.of(new SearchResult(1L, 10L, 1, 0, 0.95f, "chunk text")));
        String json = objectMapper.writeValueAsString(expected);
        when(valueOperations.get(TEST_KEY)).thenReturn(json);

        Optional<SearchResponse> result = cacheService.get(TEST_KEY, SearchResponse.class);

        assertTrue(result.isPresent());
        assertEquals("test", result.get().query());
        assertEquals(1, result.get().results().size());
    }

    @Test
    @DisplayName("get returns empty on cache miss")
    void get_cacheMiss_returnsEmpty() {
        when(valueOperations.get(TEST_KEY)).thenReturn(null);

        Optional<SearchResponse> result = cacheService.get(TEST_KEY, SearchResponse.class);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("get returns empty on Redis read failure (graceful fallback)")
    void get_redisFailure_returnsEmpty() {
        when(valueOperations.get(TEST_KEY)).thenThrow(new RuntimeException("Redis unreachable"));

        Optional<SearchResponse> result = cacheService.get(TEST_KEY, SearchResponse.class);

        assertTrue(result.isEmpty(), "Redis failure must not propagate — should return empty");
    }

    @Test
    @DisplayName("get returns empty on deserialization failure")
    void get_deserializationFailure_returnsEmpty() {
        when(valueOperations.get(TEST_KEY)).thenReturn("{invalid json");

        Optional<SearchResponse> result = cacheService.get(TEST_KEY, SearchResponse.class);

        assertTrue(result.isEmpty());
    }

    // ============================================================
    // Cache PUT (Write)
    // ============================================================

    @Test
    @DisplayName("put writes serialized JSON with TTL")
    void put_writesJsonWithTtl() throws Exception {
        SearchResponse response = new SearchResponse("test", List.of());

        cacheService.put(TEST_KEY, response, 300);

        verify(valueOperations).set(eq(TEST_KEY), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("put does not propagate exception on Redis write failure")
    void put_redisWriteFailure_doesNotPropagate() {
        doThrow(new RuntimeException("Redis down")).when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() ->
                cacheService.put(TEST_KEY, new SearchResponse("test", List.of()), 300));
    }

    // ============================================================
    // Cache EVICT
    // ============================================================

    @Test
    @DisplayName("evict deletes the key from Redis")
    void evict_deletesKey() {
        when(redisTemplate.delete(TEST_KEY)).thenReturn(true);

        cacheService.evict(TEST_KEY);

        verify(redisTemplate).delete(TEST_KEY);
    }

    @Test
    @DisplayName("evict does not propagate exception on Redis failure")
    void evict_redisFailure_doesNotPropagate() {
        when(redisTemplate.delete(TEST_KEY)).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> cacheService.evict(TEST_KEY));
    }

    // ============================================================
    // Cache Invalidation by Document Owner
    // ============================================================

    @Test
    @DisplayName("evictByDocumentOwner evicts user search, user RAG, admin search, and admin RAG patterns")
    void evictByDocumentOwner_evictsAllRelevantPatterns() {
        Set<String> userSearchKeys = Set.of("search:v1:user:42:mode:SEMANTIC:q:abc");
        Set<String> userRagKeys = Set.of("rag:v1:user:42:topK:5:q:def");
        Set<String> adminSearchKeys = Set.of("search:v1:admin:all:mode:HYBRID:q:ghi");
        Set<String> adminRagKeys = Set.of("rag:v1:admin:all:topK:5:q:jkl");

        when(redisTemplate.keys("search:v1:user:42:*")).thenReturn(userSearchKeys);
        when(redisTemplate.keys("rag:v1:user:42:*")).thenReturn(userRagKeys);
        when(redisTemplate.keys("search:v1:admin:all:*")).thenReturn(adminSearchKeys);
        when(redisTemplate.keys("rag:v1:admin:all:*")).thenReturn(adminRagKeys);
        when(redisTemplate.delete(anyCollection())).thenReturn(1L);

        cacheService.evictByDocumentOwner(42L);

        verify(redisTemplate).keys("search:v1:user:42:*");
        verify(redisTemplate).keys("rag:v1:user:42:*");
        verify(redisTemplate).keys("search:v1:admin:all:*");
        verify(redisTemplate).keys("rag:v1:admin:all:*");
        verify(redisTemplate, times(4)).delete(anyCollection());
    }

    @Test
    @DisplayName("evictByDocumentOwner does not propagate exception on Redis failure")
    void evictByDocumentOwner_redisFailure_doesNotPropagate() {
        when(redisTemplate.keys(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> cacheService.evictByDocumentOwner(42L));
    }

    // ============================================================
    // Serialization Round-Trip
    // ============================================================

    @Test
    @DisplayName("SearchResponse with rerankScore serializes and deserializes correctly")
    void serialization_searchResponseWithRerankScore() throws Exception {
        SearchResponse original = new SearchResponse("test query", List.of(
                new SearchResult(1L, 10L, 1, 0, 0.95f, "chunk text", 0.88f),
                new SearchResult(2L, 20L, 2, 1, 0.80f, "other chunk")
        ));

        String json = objectMapper.writeValueAsString(original);
        SearchResponse deserialized = objectMapper.readValue(json, SearchResponse.class);

        assertEquals(original.query(), deserialized.query());
        assertEquals(2, deserialized.results().size());
        assertEquals(0.88f, deserialized.results().get(0).rerankScore());
        assertNull(deserialized.results().get(1).rerankScore());
    }

    // ============================================================
    // isEnabled
    // ============================================================

    @Test
    @DisplayName("isEnabled returns true for RedisCacheService")
    void isEnabled_returnsTrue() {
        assertTrue(cacheService.isEnabled());
    }
}
