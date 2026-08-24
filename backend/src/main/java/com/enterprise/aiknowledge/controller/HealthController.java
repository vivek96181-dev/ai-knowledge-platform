package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check controller providing operational status of the service.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:ai-knowledge-platform}") String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Health check endpoint.
     *
     * @return 200 OK with service status and name.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> getHealthStatus() {
        return ResponseEntity.ok(HealthResponse.up(serviceName));
    }
}
