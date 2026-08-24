package com.enterprise.aiknowledge.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@link HealthController}.
 *
 * <p>Uses {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} (rather than the
 * lighter {@code @WebMvcTest}) because Java 25's class format is not yet supported
 * by the version of Byte Buddy bundled with this Spring Boot release. This means
 * {@code @MockBean} fails on Java 25. By loading the real application context with
 * the H2 test profile, we avoid mocking entirely.</p>
 *
 * <p>The health endpoint is configured as {@code permitAll()} in {@code SecurityConfig},
 * so no authentication token is needed in this test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/health should return 200 OK with UP status and service name")
    void shouldReturnHealthStatusUp() throws Exception {
        mockMvc.perform(get("/api/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                // application-test.yml sets spring.application.name to "ai-knowledge-platform-test"
                .andExpect(jsonPath("$.service").value("ai-knowledge-platform-test"));
    }
}

