package com.enterprise.aiknowledge.model;

/**
 * Represents the role of a user in the system.
 *
 * <p>Stored as a String in the database (not an integer ordinal) so the
 * table remains human-readable and adding new roles later is safe.</p>
 *
 * <ul>
 *   <li>{@code USER}  — Standard, unprivileged user account.</li>
 *   <li>{@code ADMIN} — Administrator with elevated privileges.</li>
 * </ul>
 */
public enum Role {
    USER,
    ADMIN
}
