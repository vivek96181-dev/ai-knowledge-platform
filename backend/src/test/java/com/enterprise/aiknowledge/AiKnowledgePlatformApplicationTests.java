package com.enterprise.aiknowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the Spring Boot Application Context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiKnowledgePlatformApplicationTests {

    @Test
    void contextLoads() {
        // Verifies context initialization
    }
}
