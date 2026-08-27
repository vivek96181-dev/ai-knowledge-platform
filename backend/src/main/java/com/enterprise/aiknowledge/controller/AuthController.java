package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.LoginRequest;
import com.enterprise.aiknowledge.dto.LoginResponse;
import com.enterprise.aiknowledge.dto.UserResponse;
import com.enterprise.aiknowledge.service.AuthService;
import com.enterprise.aiknowledge.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for authentication endpoints.
 *
 * <p>Base path: {@code /api/auth}</p>
 *
 * <p>This controller is intentionally thin — all logic lives in {@link AuthService}.
 * The controller handles only HTTP-level concerns: parsing the request body,
 * calling the service, and returning the correct response.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /**
     * Authenticates a user and returns a JWT token on success.
     *
     * <p>{@code @Valid} triggers Jakarta Bean Validation on the request body.
     * If email or password is blank/invalid, Spring returns 400 before reaching
     * this method — we never even attempt a database lookup with bad input.</p>
     *
     * @param request the login credentials (email + password)
     * @return HTTP 200 OK with the JWT, token type, and expiration in the body
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the profile of the currently authenticated user.
     *
     * <p>Spring MVC automatically resolves the {@link Authentication} parameter
     * from the {@code SecurityContextHolder} — no manual lookup needed.
     * The principal is the user's email, which {@link JwtAuthenticationFilter}
     * set when it validated the JWT.</p>
     *
     * <p>This endpoint requires authentication (configured in {@code SecurityConfig}).
     * If the token is missing or invalid, Spring Security returns 401 before
     * this method is ever called.</p>
     *
     * @param authentication the currently authenticated user (injected by Spring Security)
     * @return HTTP 200 OK with the user's profile (no passwordHash)
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }
}
