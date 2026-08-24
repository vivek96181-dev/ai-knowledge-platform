package com.enterprise.aiknowledge.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Service responsible for hashing plaintext passwords using BCrypt.
 *
 * <p><strong>Why BCrypt?</strong><br>
 * BCrypt is a one-way, salted hashing algorithm designed specifically for
 * passwords. Unlike MD5 or SHA, it is intentionally slow (parameterised by
 * a <em>strength/cost factor</em>), which makes brute-force attacks
 * computationally expensive.</p>
 *
 * <p><strong>Why a separate service?</strong><br>
 * Isolating password hashing keeps {@link UserService} focused on user
 * business logic. It also makes this component independently testable.</p>
 *
 * <p><strong>BCryptPasswordEncoder strength</strong><br>
 * The default strength (10) means BCrypt performs 2^10 = 1024 iterations.
 * This is a widely accepted balance between security and performance.</p>
 */
@Service
public class PasswordHashingService {

    /**
     * BCryptPasswordEncoder is thread-safe and stateless — a single shared
     * instance (managed by Spring) is sufficient for the entire application.
     */
    private final BCryptPasswordEncoder encoder;

    public PasswordHashingService() {
        // Strength 10 is the BCrypt default — adequate for most production systems.
        this.encoder = new BCryptPasswordEncoder(10);
    }

    /**
     * Hashes a plaintext password using BCrypt.
     *
     * <p>Each call generates a fresh random salt, so hashing the same password
     * twice produces two different hash strings — both of which verify correctly.</p>
     *
     * @param plaintext the raw password supplied by the user
     * @return a BCrypt hash string (60 characters, begins with {@code $2a$})
     */
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }
}
