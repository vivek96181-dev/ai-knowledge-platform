package com.enterprise.aiknowledge.exception;

/**
 * Thrown when a user attempts to register with an email address
 * that already exists in the database.
 *
 * <p>Caught by {@link GlobalExceptionHandler} and translated to
 * HTTP 409 Conflict.</p>
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email already exists: " + email);
    }
}
