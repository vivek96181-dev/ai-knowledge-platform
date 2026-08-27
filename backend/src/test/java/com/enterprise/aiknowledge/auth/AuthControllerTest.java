package com.enterprise.aiknowledge.auth;

import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.PasswordHashingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for JWT Authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private com.enterprise.aiknowledge.repository.DocumentRepository documentRepository;
    @Autowired private PasswordHashingService passwordHashingService;
    @Autowired private ObjectMapper objectMapper;

    // Injected from application-test.yml — used to build expired tokens in tests
    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final String AUTH_URL = "/api/auth";
    private static final String USERS_URL = "/api/users";

    private static final String USER_EMAIL = "user@example.com";
    private static final String USER_PASSWORD = "UserPassword123";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "AdminPassword123";

    private Long userId;

    /**
     * Before each test:
     * 1. Clear the database for a clean slate.
     * 2. Create a USER and an ADMIN with hashed passwords.
     * 3. Pre-login both to get valid JWT tokens (used in authorization tests).
     */
    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Create a regular USER account
        User user = new User();
        user.setName("Test User");
        user.setEmail(USER_EMAIL);
        user.setPasswordHash(passwordHashingService.hash(USER_PASSWORD));
        user.setRole(Role.USER);
        userId = userRepository.save(user).getId();

        // Create an ADMIN account (must set via repository since UserService always assigns USER role)
        User admin = new User();
        admin.setName("Test Admin");
        admin.setEmail(ADMIN_EMAIL);
        admin.setPasswordHash(passwordHashingService.hash(ADMIN_PASSWORD));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
    }

    // =========================================================================
    // TEST 1: Successful login returns 200 with a token
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/login → 200 OK with token when credentials are correct")
    void shouldLoginSuccessfullyWithCorrectCredentials() throws Exception {
        mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USER_EMAIL, USER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    // =========================================================================
    // TEST 2: Wrong password returns 401
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/login → 401 UNAUTHORIZED when password is wrong")
    void shouldReturn401WhenPasswordIsWrong() throws Exception {
        mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USER_EMAIL, "WrongPassword999")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // =========================================================================
    // TEST 3: Unknown email returns 401 (same message — prevents user enumeration)
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/login → 401 UNAUTHORIZED when email does not exist")
    void shouldReturn401WhenEmailDoesNotExist() throws Exception {
        mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ghost@example.com", "SomePassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // =========================================================================
    // TEST 4: JWT token is non-null and non-blank on successful login
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/login → response contains a non-blank JWT token string")
    void shouldReturnNonBlankJwtToken() throws Exception {
        MvcResult result = mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USER_EMAIL, USER_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();

        assertThat(token).isNotNull();
        assertThat(token).isNotBlank();
        // A JWT always has exactly 3 parts separated by dots: header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);
    }

    // =========================================================================
    // TEST 5: Full round-trip — login → use token on GET /api/auth/me
    // =========================================================================

    @Test
    @DisplayName("GET /api/auth/me → 200 OK when called with a valid JWT in the Authorization header")
    void shouldGrantAccessToMeEndpointWithValidToken() throws Exception {
        // Step 1: Login to get a real JWT
        String token = loginAndGetToken(USER_EMAIL, USER_PASSWORD);

        // Step 2: Call GET /api/auth/me using the token — tests the full JWT filter flow
        mockMvc.perform(get(AUTH_URL + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    // =========================================================================
    // TEST 6: Missing Authorization header → 401
    // =========================================================================

    @Test
    @DisplayName("GET /api/auth/me → 401 UNAUTHORIZED when Authorization header is missing")
    void shouldReturn401WhenAuthorizationHeaderIsMissing() throws Exception {
        // No Authorization header at all
        mockMvc.perform(get(AUTH_URL + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    // =========================================================================
    // TEST 7: Invalid/garbage token → 401
    // =========================================================================

    @Test
    @DisplayName("GET /api/auth/me → 401 UNAUTHORIZED when token is invalid/garbage")
    void shouldReturn401WhenTokenIsInvalid() throws Exception {
        mockMvc.perform(get(AUTH_URL + "/me")
                        .header("Authorization", "Bearer this.is.not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // TEST 8: Expired token → 401
    // =========================================================================

    @Test
    @DisplayName("GET /api/auth/me → 401 UNAUTHORIZED when token is expired")
    void shouldReturn401WhenTokenIsExpired() throws Exception {
        // Build an expired token directly using JJWT — expiration is 1 hour in the past
        String expiredToken = buildExpiredToken(USER_EMAIL, "USER");

        mockMvc.perform(get(AUTH_URL + "/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // TEST 9: USER can access GET /api/users/{id}
    // =========================================================================

    @Test
    @DisplayName("USER role can access GET /api/users/{id} → 200 OK")
    void userCanAccessGetUserById() throws Exception {
        // Use SecurityMockMvcRequestPostProcessors for focused authorization testing
        mockMvc.perform(get(USERS_URL + "/" + userId)
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(USER_EMAIL));
    }

    // =========================================================================
    // TEST 10: USER cannot access GET /api/users (ADMIN only) → 403
    // =========================================================================

    @Test
    @DisplayName("USER role cannot access GET /api/users (ADMIN only) → 403 FORBIDDEN")
    void userCannotAccessGetAllUsers() throws Exception {
        mockMvc.perform(get(USERS_URL)
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // =========================================================================
    // TEST 11: USER cannot DELETE /api/users/{id} (ADMIN only) → 403
    // =========================================================================

    @Test
    @DisplayName("USER role cannot DELETE /api/users/{id} (ADMIN only) → 403 FORBIDDEN")
    void userCannotDeleteUser() throws Exception {
        mockMvc.perform(delete(USERS_URL + "/" + userId)
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // TEST 12: ADMIN can access GET /api/users → 200
    // =========================================================================

    @Test
    @DisplayName("ADMIN role can access GET /api/users → 200 OK")
    void adminCanAccessGetAllUsers() throws Exception {
        mockMvc.perform(get(USERS_URL)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));  // USER + ADMIN in the DB
    }

    // =========================================================================
    // TEST 13: ADMIN can DELETE a user → 200
    // =========================================================================

    @Test
    @DisplayName("ADMIN role can DELETE /api/users/{id} → 200 OK and user is removed")
    void adminCanDeleteUser() throws Exception {
        mockMvc.perform(delete(USERS_URL + "/" + userId)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());

        // Verify the user is gone
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    // =========================================================================
    // TEST 14: GET /api/health is public — no token required
    // =========================================================================

    @Test
    @DisplayName("GET /api/health is a public endpoint — returns 200 without any token")
    void healthEndpointIsPublic() throws Exception {
        // No Authorization header, no mock user — must succeed
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // =========================================================================
    // TEST 15: POST /api/auth/login is public — no prior token required
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/login is public — can be called without any existing token")
    void loginEndpointIsPublic() throws Exception {
        // Calling login without ANY prior authentication should succeed
        mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Performs a real login HTTP call and returns the raw JWT string.
     * Used in TEST 5 to test the full round-trip without mock users.
     */
    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(AUTH_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    /**
     * Builds an already-expired JWT using JJWT directly — used in TEST 8.
     *
     * <p>Expiration is set 1 hour in the past so {@code JwtService.isTokenValid()}
     * will catch the {@code ExpiredJwtException} and return {@code false}.</p>
     */
    private String buildExpiredToken(String email, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long nowMs = System.currentTimeMillis();

        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date(nowMs - 7_200_000))   // issued 2 hours ago
                .expiration(new Date(nowMs - 3_600_000)) // expired 1 hour ago
                .signWith(key)
                .compact();
    }

    /**
     * Builds the JSON body for a login request.
     */
    private String loginBody(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
    }
}
