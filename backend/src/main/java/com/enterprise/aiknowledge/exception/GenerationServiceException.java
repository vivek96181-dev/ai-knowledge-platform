package com.enterprise.aiknowledge.exception;

/**
 * Exception thrown when the LLM text generation service encounters an unrecoverable failure
 * (e.g. permanent API error, empty response, or max retries exceeded).
 */
public class GenerationServiceException extends RuntimeException {

    public GenerationServiceException(String message) {
        super(message);
    }

    public GenerationServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
