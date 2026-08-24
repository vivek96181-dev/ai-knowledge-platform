package com.enterprise.aiknowledge.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing a registered user.
 *
 * <p><strong>Table mapping:</strong> Maps to the {@code users} table in PostgreSQL.
 * The table name is explicitly set to {@code users} (not {@code user}) because
 * {@code USER} is a reserved keyword in both H2 and PostgreSQL.</p>
 *
 * <p><strong>Password storage:</strong> Only {@code passwordHash} (a BCrypt hash)
 * is stored — the plaintext password is <em>never</em> persisted.</p>
 *
 * <p><strong>Timestamps:</strong> {@code createdAt} is set once at insert time by
 * Hibernate. {@code updatedAt} is automatically updated on every write.</p>
 */
@Entity
@Table(name = "users")  // Explicit name avoids collision with the SQL reserved keyword USER
public class User {

    /**
     * Primary key. {@code IDENTITY} lets the database (PostgreSQL SERIAL / H2 IDENTITY)
     * auto-increment the value. We never set this manually.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user's display name. Required — the column cannot be null.
     */
    @Column(nullable = false)
    private String name;

    /**
     * The user's email address. Must be unique across all users.
     * {@code nullable = false} enforces it at the DB level too.
     */
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * BCrypt hash of the user's password.
     * The raw password is <strong>never stored here</strong>.
     */
    @Column(nullable = false)
    private String passwordHash;

    /**
     * The user's role. Stored as a String (e.g. "USER", "ADMIN") rather than
     * an integer ordinal, so the table stays readable and reordering enum values
     * is safe.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * Timestamp of when this row was first inserted.
     * Set automatically by Hibernate; never updated afterward.
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the last update to this row.
     * Automatically refreshed by Hibernate on every save/update.
     */
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // JPA requires a public no-args constructor for entity instantiation.
    // -------------------------------------------------------------------------
    public User() {}

    // -------------------------------------------------------------------------
    // Getters — used by UserService to read entity fields.
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // -------------------------------------------------------------------------
    // Setters — used by UserService to populate fields before saving.
    // -------------------------------------------------------------------------

    public void setName(String name) {
        this.name = name;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
