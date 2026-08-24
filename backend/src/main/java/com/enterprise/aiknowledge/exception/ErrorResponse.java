package com.enterprise.aiknowledge.exception;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standardized error payload returned across all REST endpoints.
 *
 * @param timestamp  Time at which the error occurred
 * @param status     HTTP status code
 * @param error      Short error description (e.g. "Not Found", "Bad Request")
 * @param message    Human-readable detail message
 * @param path       The request URI path where the error originated
 * @param errors     Optional list of validation errors for form/input validation
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> errors
) {
    public ErrorResponse(int status, String error, String message, String path) {
        this(LocalDateTime.now(), status, error, message, path, null);
    }

    public ErrorResponse(int status, String error, String message, String path, List<String> errors) {
        this(LocalDateTime.now(), status, error, message, path, errors);
    }
}
