package com.enterprise.aiknowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * No-operation {@link CacheService} implementation used when caching is disabled.
 *
 * <p>Activated when {@code cache.enabled=false} (the default in test profiles).
 * All operations are no-ops — reads always return empty, writes and evictions are silently ignored.
 * This ensures the application functions identically with or without Redis infrastructure.</p>
 */
@Service
@ConditionalOnProperty(name = "cache.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(NoOpCacheService.class);

    public NoOpCacheService() {
        log.info("NoOpCacheService initialized (cache disabled)");
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        // No-op
    }

    @Override
    public void evict(String key) {
        // No-op
    }

    @Override
    public void evictByDocumentOwner(Long ownerUserId) {
        // No-op
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
