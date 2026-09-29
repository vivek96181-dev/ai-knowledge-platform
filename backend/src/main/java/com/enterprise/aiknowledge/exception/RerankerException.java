package com.enterprise.aiknowledge.exception;

/**
 * Exception thrown when query-to-chunk reranking fails.
 */
public class RerankerException extends RuntimeException {

    public RerankerException(String message) {
        super(message);
    }

    public RerankerException(String message, Throwable cause) {
        super(message, cause);
    }
}
