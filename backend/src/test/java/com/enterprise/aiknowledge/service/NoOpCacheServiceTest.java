package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NoOpCacheService — Disabled Cache Behavior Tests")
class NoOpCacheServiceTest {

    private NoOpCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new NoOpCacheService();
    }

    @Test
    @DisplayName("isEnabled returns false")
    void isEnabled_returnsFalse() {
        assertFalse(cacheService.isEnabled());
    }

    @Test
    @DisplayName("get always returns empty")
    void get_alwaysReturnsEmpty() {
        Optional<SearchResponse> result = cacheService.get("any-key", SearchResponse.class);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("put does not throw")
    void put_doesNotThrow() {
        assertDoesNotThrow(() ->
                cacheService.put("key", new SearchResponse("test", List.of()), 300));
    }

    @Test
    @DisplayName("evict does not throw")
    void evict_doesNotThrow() {
        assertDoesNotThrow(() -> cacheService.evict("key"));
    }

    @Test
    @DisplayName("evictByDocumentOwner does not throw")
    void evictByDocumentOwner_doesNotThrow() {
        assertDoesNotThrow(() -> cacheService.evictByDocumentOwner(42L));
    }
}
