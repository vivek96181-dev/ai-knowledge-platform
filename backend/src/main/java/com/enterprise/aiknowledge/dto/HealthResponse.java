package com.enterprise.aiknowledge.dto;

/**
 * Data Transfer Object representing the health status response.
 *
 * @param status  The health status of the service (e.g., "UP")
 * @param service The name of the service (e.g., "ai-knowledge-platform")
 */
public record HealthResponse(
        String status,
        String service
) {
    public static HealthResponse up(String serviceName) {
        return new HealthResponse("UP", serviceName);
    }
}
