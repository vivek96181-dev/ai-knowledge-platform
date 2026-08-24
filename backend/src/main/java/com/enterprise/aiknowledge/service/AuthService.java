package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.LoginRequest;
import com.enterprise.aiknowledge.dto.LoginResponse;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/**
 * Service that handles user authentication (login).
 *
 * <p>This is intentionally separate from {@link UserService} because authentication
 * is a distinct concern from user management (CRUD). Mixing them would violate
 * the Single Responsibility Principle and make the class harder to reason about.</p>
 *
 * <p><strong>Login flow:</strong></p>
 * <pre>
 *   1. Find user by email.
 *   2. Verify password against stored BCrypt hash.
 *   3. Generate a signed JWT with email + role claims.
 *   4. Return token to the client.
 * </pre>
 *
 * <p><strong>Security note — generic error message:</strong><br>
 * Both "user not found" and "wrong password" throw the same exception with the
 * same message: "Invalid email or password". This is intentional — revealing
 * which one is wrong would allow an attacker to enumerate valid email addresses
 * (a user enumeration attack).</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordHashingService passwordHashingService;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordHashingService passwordHashingService,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordHashingService = passwordHashingService;
        this.jwtService = jwtService;
    }

    /**
     * Authenticates a user and returns a JWT on success.
     *
     * @param request the login request containing email and password
     * @return a {@link LoginResponse} containing the signed JWT token
     * @throws BadCredentialsException if the email is not found or the password is wrong
     */
    public LoginResponse login(LoginRequest request) {
        // Step 1: Look up the user — use the same generic error message whether
        //         the email exists or not, to prevent user enumeration attacks.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // Step 2: Verify the provided password against the stored BCrypt hash.
        //         BCrypt.matches() is timing-safe — it takes constant time regardless
        //         of where the comparison fails, preventing timing attacks.
        if (!passwordHashingService.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        // Step 3: Credentials verified — generate a signed JWT with email and role
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());

        // Step 4: Return the token with metadata the client needs
        return new LoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationMs() / 1000   // Convert ms to seconds for the response
        );
    }
}
