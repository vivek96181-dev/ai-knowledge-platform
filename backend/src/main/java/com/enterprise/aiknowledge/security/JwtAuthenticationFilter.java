package com.enterprise.aiknowledge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT Authentication Filter — the gateway that enforces stateless authentication.
 *
 * <p>This filter runs <strong>once per HTTP request</strong> (guaranteed by
 * {@link OncePerRequestFilter}) and sits BEFORE Spring Security's default
 * {@code UsernamePasswordAuthenticationFilter} in the filter chain.</p>
 *
 * <p><strong>What it does:</strong></p>
 * <pre>
 *   1. Read the "Authorization" header.
 *   2. If header is missing or not "Bearer ..." → skip (no auth set, Security handles 401).
 *   3. Extract the raw JWT string after "Bearer ".
 *   4. Ask JwtService: is this token valid (good signature + not expired)?
 *   5. If invalid → skip (Security handles 401).
 *   6. If valid → extract email and role from the token claims.
 *   7. Build a UsernamePasswordAuthenticationToken (Spring Security's auth object).
 *   8. Store it in the SecurityContext so downstream filters and controllers see it.
 * </pre>
 *
 * <p><strong>No database call:</strong> The email and role are read directly from
 * the JWT claims. The token's HMAC signature guarantees they haven't been tampered
 * with — no DB lookup required to authenticate each request.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Authorization header, or doesn't start with "Bearer " — skip this filter.
        // Spring Security will then check if the endpoint is public or return 401.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Strip the "Bearer " prefix (7 characters) to get the raw JWT string
        String token = authHeader.substring(7);

        // Let JwtService verify the signature and check expiration
        if (!jwtService.isTokenValid(token)) {
            // Token is present but invalid/expired — skip, Security will return 401
            filterChain.doFilter(request, response);
            return;
        }

        // Token is valid — extract the user's identity and role from the claims
        String email = jwtService.extractEmail(token);
        String role = jwtService.extractRole(token);

        // Spring Security's hasRole("ADMIN") checks for "ROLE_ADMIN" internally,
        // so we must prefix the role value from the token with "ROLE_".
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role);

        // Build a Spring Security authentication object.
        // Constructor: (principal, credentials, authorities)
        // - principal = email (used by AuthController.getCurrentUser() to identify the caller)
        // - credentials = null (we don't need the password after authentication)
        // - authorities = list with a single role-based authority
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(email, null, List.of(authority));

        // Attach request metadata (IP address, session ID) — useful for audit logging
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // Place the authentication into the SecurityContext.
        // From this point forward, any code in this request can call
        // SecurityContextHolder.getContext().getAuthentication() to get the user.
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Continue down the filter chain to the controller
        filterChain.doFilter(request, response);
    }
}
