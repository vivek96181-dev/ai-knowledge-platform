package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.CreateUserRequest;
import com.enterprise.aiknowledge.dto.UserResponse;
import com.enterprise.aiknowledge.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller that exposes CRUD endpoints for user management.
 *
 * <p>Base path: {@code /api/users}</p>
 *
 * <p>The controller is intentionally thin — it handles only HTTP concerns:
 * parsing request bodies, calling the service, and returning the correct
 * HTTP status codes. All business logic lives in {@link UserService}.</p>
 *
 * <p><strong>Interview tip:</strong> This is the standard Spring MVC request flow:
 * <pre>
 *   HTTP Request
 *       ↓ (Tomcat / DispatcherServlet)
 *   @RestController  ← You are here
 *       ↓
 *   @Service
 *       ↓
 *   JpaRepository
 *       ↓
 *   PostgreSQL
 * </pre>
 * </p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Creates a new user account.
     *
     * <p>{@code @Valid} triggers Jakarta Bean Validation on the request body.
     * If any constraint fails (e.g. blank name, invalid email, short password),
     * Spring rejects the request before it even reaches this method and
     * {@link com.enterprise.aiknowledge.exception.GlobalExceptionHandler} returns a 400.</p>
     *
     * @param request the validated create-user payload
     * @return HTTP 201 CREATED with the saved user as the body
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Returns a list of all registered users.
     *
     * @return HTTP 200 OK with a JSON array of users (may be empty)
     */
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    /**
     * Returns a single user by ID.
     *
     * @param id the user's database primary key (from the URL path)
     * @return HTTP 200 OK with the user body, or HTTP 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    /**
     * Deletes a user by ID.
     *
     * @param id the user's database primary key (from the URL path)
     * @return HTTP 200 OK with no body, or HTTP 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }
}
