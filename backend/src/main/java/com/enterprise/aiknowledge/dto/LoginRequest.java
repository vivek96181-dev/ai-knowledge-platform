package com.enterprise.aiknowledge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the POST /api/auth/login endpoint.
 *
 * <p>Jakarta Validation annotations here ensure that Spring rejects malformed
 * login attempts before they reach {@code AuthService}, returning a clean
 * 400 Bad Request for obviously invalid input.</p>
 *
 * @param email    The user's registered email address
 * @param password The user's plaintext password (never stored — only used for BCrypt verification)
 */
public record LoginRequest(

        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password must not be blank")
        String password

) {}
