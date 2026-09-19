package com.enterprise.aiknowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for Retrieval-Augmented Generation (POST /api/rag/ask).
 *
 * @param query Natural-language question asked by the user
 * @param topK  Optional number of relevant document chunks to retrieve for context
 */
public record RagRequest(
        @NotBlank(message = "Query cannot be blank")
        @Size(max = 1000, message = "Query cannot exceed 1000 characters")
        String query,

        Integer topK
) {}
