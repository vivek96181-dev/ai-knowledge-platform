package com.enterprise.aiknowledge.user;

import com.enterprise.aiknowledge.dto.UserResponse;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the User Management API.
 *
 * <p><strong>Strategy:</strong>
 * {@code @SpringBootTest} loads the full Spring context.
 * {@code @AutoConfigureMockMvc} sets up MockMvc to test HTTP endpoints without
 * starting a real server.
 * {@code @ActiveProfiles("test")} activates {@code application-test.yml},
 * which configures an H2 in-memory database — no PostgreSQL required to run tests.</p>
 *
 * <p>Each test method gets a clean database state via {@link #clearDatabase()},
 * which runs before every test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // Reusable BCrypt encoder for verifying stored hashes in tests
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private static final String BASE_URL = "/api/users";

    // JSON payload for a valid new user
    private static final String VALID_CREATE_REQUEST = """
            {
              "name": "Vivek",
              "email": "vivek@example.com",
              "password": "MySecurePassword123"
            }
            """;

    /**
     * Delete all users before every test so each test starts with a clean database.
     * This prevents test-ordering dependencies.
     */
    @BeforeEach
    void clearDatabase() {
        userRepository.deleteAll();
    }

    // =========================================================================
    // TEST 1: Create user successfully
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → 201 CREATED with correct user fields")
    void shouldCreateUserSuccessfully() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Vivek"))
                .andExpect(jsonPath("$.email").value("vivek@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    // =========================================================================
    // TEST 2: Password is stored as a BCrypt hash (not plaintext)
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → password is stored as a BCrypt hash in the database")
    void shouldStorePasswordAsBcryptHash() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated());

        // Fetch directly from the repository to inspect what was actually stored
        var savedUser = userRepository.findByEmail("vivek@example.com").orElseThrow();

        // Verify it is NOT the plain text
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("MySecurePassword123");

        // Verify it IS a valid BCrypt hash for the original password
        assertThat(encoder.matches("MySecurePassword123", savedUser.getPasswordHash())).isTrue();

        // Verify it starts with the BCrypt prefix
        assertThat(savedUser.getPasswordHash()).startsWith("$2a$");
    }

    // =========================================================================
    // TEST 3: passwordHash is NEVER returned in the API response
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → response must never contain passwordHash field")
    void shouldNeverReturnPasswordHashInResponse() throws Exception {
        String responseBody = mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The JSON response must NOT contain any password-related field
        assertThat(responseBody).doesNotContain("password");
        assertThat(responseBody).doesNotContain("passwordHash");
        assertThat(responseBody).doesNotContain("$2a$");
    }

    // =========================================================================
    // TEST 4: Duplicate email is rejected with 409 CONFLICT
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → 409 CONFLICT when email already exists")
    void shouldRejectDuplicateEmail() throws Exception {
        // Create the user once
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated());

        // Attempt to create with the same email again
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already exists: vivek@example.com"));
    }

    // =========================================================================
    // TEST 5: Get user by ID
    // =========================================================================

    @Test
    @DisplayName("GET /api/users/{id} → 200 OK with correct user data")
    void shouldGetUserById() throws Exception {
        // Create a user and capture the returned ID
        MvcResult createResult = mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andReturn();

        UserResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), UserResponse.class);

        // Now fetch by that ID
        mockMvc.perform(get(BASE_URL + "/" + created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.email").value("vivek@example.com"))
                .andExpect(jsonPath("$.name").value("Vivek"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    // =========================================================================
    // TEST 6: User not found returns 404
    // =========================================================================

    @Test
    @DisplayName("GET /api/users/{id} → 404 NOT FOUND for non-existent ID")
    void shouldReturn404ForNonExistentUser() throws Exception {
        mockMvc.perform(get(BASE_URL + "/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User not found with id: 99999"));
    }

    // =========================================================================
    // TEST 7: Get all users
    // =========================================================================

    @Test
    @DisplayName("GET /api/users → 200 OK with list of all users")
    void shouldGetAllUsers() throws Exception {
        // Create two users
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice",
                                  "email": "alice@example.com",
                                  "password": "AlicePassword123"
                                }
                                """))
                .andExpect(status().isCreated());

        // Fetch all and verify count and content
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value("vivek@example.com"))
                .andExpect(jsonPath("$[1].email").value("alice@example.com"));
    }

    // =========================================================================
    // TEST 8: Delete user
    // =========================================================================

    @Test
    @DisplayName("DELETE /api/users/{id} → 200 OK, user is removed from database")
    void shouldDeleteUser() throws Exception {
        // Create a user
        MvcResult createResult = mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andReturn();

        UserResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), UserResponse.class);

        // Delete the user
        mockMvc.perform(delete(BASE_URL + "/" + created.id()))
                .andExpect(status().isOk());

        // Verify the user is truly gone (404 on subsequent GET)
        mockMvc.perform(get(BASE_URL + "/" + created.id()))
                .andExpect(status().isNotFound());

        // Verify repository is empty
        assertThat(userRepository.count()).isZero();
    }

    // =========================================================================
    // TEST 9: Validation failure returns 400 BAD REQUEST
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → 400 BAD REQUEST when name is blank")
    void shouldReturn400WhenNameIsBlank() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "email": "vivek@example.com",
                                  "password": "MySecurePassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/users → 400 BAD REQUEST when email is invalid")
    void shouldReturn400WhenEmailIsInvalid() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Vivek",
                                  "email": "not-an-email",
                                  "password": "MySecurePassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/users → 400 BAD REQUEST when password is too short")
    void shouldReturn400WhenPasswordIsTooShort() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Vivek",
                                  "email": "vivek@example.com",
                                  "password": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // =========================================================================
    // TEST 10: Role defaults to USER
    // =========================================================================

    @Test
    @DisplayName("POST /api/users → new users always receive the USER role")
    void shouldAssignUserRoleByDefault() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        // Verify at the database level too
        var user = userRepository.findByEmail("vivek@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }
}
