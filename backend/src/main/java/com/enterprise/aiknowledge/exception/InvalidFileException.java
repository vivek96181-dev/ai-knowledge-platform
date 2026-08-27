package com.enterprise.aiknowledge.exception;

/**
 * Exception thrown when an uploaded file fails validation rules
 * (e.g. empty file, missing file, non-PDF file type, invalid filename).
 *
 * <p>Translates to HTTP 400 Bad Request via {@link GlobalExceptionHandler}.</p>
 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
