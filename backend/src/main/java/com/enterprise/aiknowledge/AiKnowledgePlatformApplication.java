package com.enterprise.aiknowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Main entry point for the Enterprise AI Knowledge Platform backend.
 *
 * <p>We exclude {@link UserDetailsServiceAutoConfiguration} because we manage our own
 * authentication via JWT — we don't need Spring Boot's default in-memory user and
 * generated password, which would produce a noisy startup warning.</p>
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class AiKnowledgePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiKnowledgePlatformApplication.class, args);
    }
}
