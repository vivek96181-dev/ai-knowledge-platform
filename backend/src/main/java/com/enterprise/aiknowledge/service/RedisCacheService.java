package com.enterprise.aiknowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed implementation of {@link CacheService}.
 *
 * <p><strong>Failure Behavior:</strong> Redis is an optimization, not the source of truth.
 * All Redis operations are wrapped in try-catch blocks. On any failure (connection refused,
 * serialization error, timeout), the operation logs a WARN and returns gracefully.
 * A healthy search request is never turned into an HTTP 500 solely because Redis is down.</p>
 *
 * <p><strong>Serialization:</strong> Uses Jackson {@link ObjectMapper} for JSON serialization
 * of {@link com.enterprise.aiknowledge.dto.SearchResponse} and
 * {@link com.enterprise.aiknowledge.dto.RagResponse} records. Java native serialization
 * is never used. JPA entities are never cached.</p>
 *
 * <p><strong>Invalidation:</strong> Uses Redis SCAN-based key pattern matching via
 * {@link StringRedisTemplate#keys(String)} for owner-scoped invalidation.
 * Never calls {@code FLUSHALL} or {@code FLUSHDB}.</p>
 */
@Service
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper cacheObjectMapper;
    private final CacheKeyFactory cacheKeyFactory;

    public RedisCacheService(
            StringRedisTemplate redisTemplate,
            @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper,
            CacheKeyFactory cacheKeyFactory) {
        this.redisTemplate = redisTemplate;
        this.cacheObjectMapper = cacheObjectMapper;
        this.cacheKeyFactory = cacheKeyFactory;
        log.info("RedisCacheService initialized (cache enabled)");
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("Cache MISS: {}", key);
                return Optional.empty();
            }
            T value = cacheObjectMapper.readValue(json, type);
            log.info("Cache HIT: {}", key);
            return Optional.of(value);
        } catch (Exception e) {
            log.warn("Cache read failure for key [{}]: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        try {
            String json = cacheObjectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
            log.debug("Cache PUT: {} (TTL: {}s)", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("Cache write failure for key [{}]: {}", key, e.getMessage());
        }
    }

    @Override
    public void evict(String key) {
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Cache EVICT: {}", key);
            }
        } catch (Exception e) {
            log.warn("Cache evict failure for key [{}]: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictByDocumentOwner(Long ownerUserId) {
        try {
            int totalEvicted = 0;

            // Evict user-scoped search and RAG entries
            totalEvicted += evictByPattern(cacheKeyFactory.buildUserSearchPattern(ownerUserId));
            totalEvicted += evictByPattern(cacheKeyFactory.buildUserRagPattern(ownerUserId));

            // Evict ADMIN-scoped entries (ADMIN search spans all users' docs)
            totalEvicted += evictByPattern(cacheKeyFactory.buildAdminSearchPattern());
            totalEvicted += evictByPattern(cacheKeyFactory.buildAdminRagPattern());

            log.info("Cache invalidation for document owner [userId={}]: {} entries evicted",
                    ownerUserId, totalEvicted);
        } catch (Exception e) {
            log.warn("Cache invalidation failure for owner [userId={}]: {}",
                    ownerUserId, e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Evicts all keys matching a Redis glob pattern.
     * Uses {@link StringRedisTemplate#keys(String)} for key discovery.
     *
     * @param pattern Redis key pattern (e.g., {@code search:v1:user:42:*})
     * @return number of keys evicted
     */
    private int evictByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            Long deleted = redisTemplate.delete(keys);
            return deleted != null ? deleted.intValue() : 0;
        }
        return 0;
    }
}
