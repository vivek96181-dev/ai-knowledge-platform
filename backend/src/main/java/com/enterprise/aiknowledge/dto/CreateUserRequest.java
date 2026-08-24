package com.enterprise.aiknowledge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new user.
 *
 * <p>Contains the raw, plaintext {@code password} supplied by the caller.
 * The password is <strong>never persisted</strong> — it is hashed by
 * {@code PasswordHashingService} before the entity is saved to the database.</p>
 *
 * <p>Uses Jakarta Bean Validation annotations so Spring automatically
 * rejects invalid requests with HTTP 400 before they reach the service.</p>
 *
 * @param name     Full name of the user — must not be blank
 * @param email    Email address — must be well-formed and unique in the system
 * @param password Plaintext password — minimum 8 characters
 */
public record CreateUserRequest(

        @NotBlank(message = "Name must not be blank")
        String name,

        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password must not be blank")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password

) {}
