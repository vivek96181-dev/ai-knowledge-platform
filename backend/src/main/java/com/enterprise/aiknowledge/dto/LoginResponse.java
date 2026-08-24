package com.enterprise.aiknowledge.dto;

/**
 * Response body returned after a successful POST /api/auth/login.
 *
 * <p>The client must store {@code accessToken} and send it on every subsequent
 * request in the {@code Authorization: Bearer <token>} header.</p>
 *
 * @param accessToken The signed JWT string — contains email, role, and expiration claims
 * @param tokenType   Always {@code "Bearer"} — tells the client how to send the token
 * @param expiresIn   Seconds until the token expires (e.g. 3600 = 1 hour)
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {}
