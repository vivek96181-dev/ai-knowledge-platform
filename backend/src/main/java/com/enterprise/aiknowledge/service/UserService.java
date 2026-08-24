package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.CreateUserRequest;
import com.enterprise.aiknowledge.dto.UserResponse;
import com.enterprise.aiknowledge.exception.EmailAlreadyExistsException;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for user management business logic.
 *
 * <p>This class is the heart of the user feature. It orchestrates:</p>
 * <ul>
 *   <li>Duplicate email detection (before any insert)</li>
 *   <li>Password hashing (delegated to {@link PasswordHashingService})</li>
 *   <li>Persistence via {@link UserRepository}</li>
 *   <li>Entity-to-DTO mapping (so controllers never touch the entity directly)</li>
 * </ul>
 *
 * <p><strong>Why keep mapping here?</strong><br>
 * Moving the entity-to-DTO conversion into the service keeps controllers thin.
 * The controller only handles HTTP concerns (status codes, request/response bodies);
 * the service handles all business concerns.</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordHashingService passwordHashingService;

    /**
     * Constructor injection — preferred over @Autowired field injection.
     * Makes dependencies explicit, enables easier unit testing with mocks,
     * and prevents circular-dependency bugs from going undetected at startup.
     */
    public UserService(UserRepository userRepository, PasswordHashingService passwordHashingService) {
        this.userRepository = userRepository;
        this.passwordHashingService = passwordHashingService;
    }

    /**
     * Creates a new user account.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Check that the email is not already registered → throw {@link EmailAlreadyExistsException} if so.</li>
     *   <li>Hash the plaintext password with BCrypt.</li>
     *   <li>Set role to {@link Role#USER} (the default for all new accounts).</li>
     *   <li>Save the entity.</li>
     *   <li>Return a {@link UserResponse} — no passwordHash exposed.</li>
     * </ol>
     *
     * @param request the validated create-user request
     * @return the saved user represented as a {@link UserResponse}
     * @throws EmailAlreadyExistsException if the email is already registered
     */
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordHashingService.hash(request.password()));
        user.setRole(Role.USER); // New users always start with USER role

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    /**
     * Returns all registered users.
     *
     * @return a list of {@link UserResponse} objects — never {@code null}, may be empty
     */
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * Returns a single user by their database ID.
     *
     * @param id the user's primary key
     * @return the user as a {@link UserResponse}
     * @throws ResourceNotFoundException if no user with this ID exists
     */
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return mapToResponse(user);
    }

    /**
     * Returns a single user by their email address.
     *
     * <p>Used by {@code AuthController.getCurrentUser()} to fetch the full user
     * record from the email stored as the JWT principal.</p>
     *
     * @param email the user's email address
     * @return the user as a {@link UserResponse}
     * @throws ResourceNotFoundException if no user with this email exists
     */
    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        return mapToResponse(user);
    }

    /**
     * Deletes a user by their database ID.
     *
     * <p>We verify existence first so we can throw a meaningful 404 rather
     * than silently completing on a non-existent ID (which {@code deleteById}
     * would do if not guarded).</p>
     *
     * @param id the user's primary key
     * @throws ResourceNotFoundException if no user with this ID exists
     */
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }

    /**
     * Converts a {@link User} entity into a {@link UserResponse} DTO.
     *
     * <p>This is a private helper — only this service should perform this mapping.
     * Critically, {@code passwordHash} is intentionally <strong>not</strong> included.</p>
     *
     * @param user the entity to convert
     * @return the corresponding response DTO
     */
    private UserResponse mapToResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
