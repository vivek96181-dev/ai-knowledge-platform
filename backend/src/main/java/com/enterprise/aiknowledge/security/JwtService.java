package com.enterprise.aiknowledge.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Service that encapsulates all JWT logic: generation, validation, and claim extraction.
 *
 * <p><strong>JWT Structure (3 Base64-encoded parts separated by dots):</strong></p>
 * <pre>
 *   Header.Payload.Signature
 *
 *   Header:  {"alg":"HS256","typ":"JWT"}
 *   Payload: {"sub":"vivek@example.com","role":"USER","iat":...,"exp":...}
 *   Signature: HMAC-SHA256(base64(header) + "." + base64(payload), secret)
 * </pre>
 *
 * <p><strong>Algorithm — HMAC-SHA256 (HS256):</strong><br>
 * A symmetric algorithm — the same secret key is used to sign AND verify. This means
 * only our server can create or validate tokens. Asymmetric (RS256) is better for
 * distributed systems where different services verify tokens; HS256 is simpler
 * and perfectly appropriate for a single-server deployment.</p>
 *
 * <p><strong>No database lookup on every request:</strong><br>
 * The email and role are embedded in the token itself. The filter reads them directly
 * from the claims — no database query is needed to authenticate each request.</p>
 */
@Service
public class JwtService {

    private final String secret;
    private final long expirationMs;

    /**
     * Constructor injection of JWT configuration from application.yml / environment variables.
     *
     * @param secret       The HMAC-SHA256 signing key (must be ≥ 32 characters for security)
     * @param expirationMs Token lifetime in milliseconds (e.g. 3600000 = 1 hour)
     */
    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    /**
     * Generates a signed JWT token for the given user.
     *
     * <p>Claims embedded in the token:</p>
     * <ul>
     *   <li>{@code sub} — The user's email (used as the subject/principal identifier)</li>
     *   <li>{@code role} — The user's role (e.g. "USER", "ADMIN")</li>
     *   <li>{@code iat} — Issued-at timestamp</li>
     *   <li>{@code exp} — Expiration timestamp</li>
     * </ul>
     *
     * @param email the user's email address (becomes the JWT subject)
     * @param role  the user's role name (e.g. "USER" or "ADMIN")
     * @return a compact, URL-safe JWT string ready to send to the client
     */
    public String generateToken(String email, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)                  // "sub" claim — identifies the user
                .claim("role", role)             // Custom "role" claim for authorization
                .issuedAt(now)                   // "iat" claim
                .expiration(expiration)          // "exp" claim
                .signWith(getSigningKey())        // Signs with HMAC-SHA256
                .compact();                      // Produces the final "header.payload.signature" string
    }

    /**
     * Validates a JWT token by checking:
     * <ol>
     *   <li>That the signature is valid (not tampered with)</li>
     *   <li>That the token has not expired</li>
     * </ol>
     *
     * <p>If either check fails, JJWT throws a {@link JwtException} subclass,
     * which we catch and convert to a simple {@code false}.</p>
     *
     * @param token the JWT string to validate
     * @return {@code true} if the token is valid and unexpired
     */
    public boolean isTokenValid(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())  // Verify signature
                    .build()
                    .parseSignedClaims(token);   // Also checks expiration automatically
            return true;
        } catch (JwtException e) {
            // Covers: SignatureException, ExpiredJwtException, MalformedJwtException, etc.
            return false;
        }
    }

    /**
     * Extracts the email address (JWT {@code sub} claim) from a valid token.
     *
     * @param token a valid (non-expired) JWT string
     * @return the user's email address
     */
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Extracts the role (custom {@code role} claim) from a valid token.
     *
     * @param token a valid (non-expired) JWT string
     * @return the user's role name (e.g. "USER" or "ADMIN")
     */
    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    /**
     * Returns the configured token lifetime in milliseconds.
     * Used by {@code AuthService} to populate the {@code expiresIn} field in the response.
     */
    public long getExpirationMs() {
        return expirationMs;
    }

    /**
     * Parses and returns all claims from the JWT payload.
     *
     * <p>Only call this after {@link #isTokenValid(String)} returns {@code true}.
     * Throws {@link JwtException} if the token is invalid or expired.</p>
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Derives the {@link SecretKey} from the configured secret string.
     *
     * <p>JJWT's {@code Keys.hmacShaKeyFor()} validates that the key is long
     * enough for the algorithm (≥ 256 bits for HS256) and wraps it in a
     * {@link SecretKey} object suitable for signing and verification.</p>
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
