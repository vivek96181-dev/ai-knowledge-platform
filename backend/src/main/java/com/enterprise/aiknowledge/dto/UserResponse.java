package com.enterprise.aiknowledge.dto;

import com.enterprise.aiknowledge.model.Role;

import java.time.LocalDateTime;

/**
 * Response body returned by the User API endpoints.
 *
 * <p><strong>Security:</strong> This record deliberately omits
 * {@code passwordHash}. The hashed password is an internal implementation
 * detail and must never be sent to any API caller.</p>
 *
 * @param id        Unique database-generated identifier
 * @param name      User's full name
 * @param email     User's email address
 * @param role      User's role (USER or ADMIN)
 * @param createdAt When the user account was created
 * @param updatedAt When the user account was last modified
 */
public record UserResponse(
        Long id,
        String name,
        String email,
        Role role,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
