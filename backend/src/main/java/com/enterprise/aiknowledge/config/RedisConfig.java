package com.enterprise.aiknowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis configuration providing a {@link StringRedisTemplate} for cache operations.
 *
 * <p>Only activated when {@code cache.enabled=true} (default in production).
 * Tests disable Redis auto-configuration entirely so no real Redis server is required.</p>
 *
 * <p><strong>Serialization Strategy:</strong> Uses {@link StringRedisTemplate} with
 * manual Jackson JSON serialization via a dedicated {@link ObjectMapper}. This avoids
 * storing Java class metadata in Redis values, keeping cached data versionable and
 * technology-neutral.</p>
 */
@Configuration
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
public class RedisConfig {

    /**
     * Dedicated ObjectMapper for cache serialization.
     * <p>Configured independently of the application's web ObjectMapper to avoid
     * coupling cache serialization to API serialization changes.</p>
     */
    @Bean(name = "cacheObjectMapper")
    public ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    /**
     * StringRedisTemplate using the auto-configured Lettuce connection factory.
     * <p>All cache values are stored as JSON strings, never as Java-serialized bytes.</p>
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
