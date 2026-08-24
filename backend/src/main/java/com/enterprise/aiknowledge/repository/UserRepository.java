package com.enterprise.aiknowledge.repository;

import com.enterprise.aiknowledge.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link User} entity.
 *
 * <p>By extending {@link JpaRepository}, we get the full CRUD suite for free:
 * {@code save()}, {@code findById()}, {@code findAll()}, {@code deleteById()}, etc.
 * No implementation class is needed — Spring generates the proxy at startup.</p>
 *
 * <p>The two custom methods below use Spring Data's method-name derivation:
 * Spring parses the method name and generates the correct SQL query automatically.</p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by their email address.
     *
     * <p>Generated SQL: {@code SELECT * FROM users WHERE email = ?}</p>
     *
     * @param email the email address to search for
     * @return an {@link Optional} containing the user if found, or empty if not
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether a user with the given email already exists.
     *
     * <p>More efficient than {@link #findByEmail} for duplicate-checking
     * because it generates a {@code SELECT COUNT} or {@code EXISTS} query
     * rather than loading the entire row.</p>
     *
     * <p>Generated SQL: {@code SELECT COUNT(*) > 0 FROM users WHERE email = ?}</p>
     *
     * @param email the email address to check
     * @return {@code true} if a user with this email exists
     */
    boolean existsByEmail(String email);
}
