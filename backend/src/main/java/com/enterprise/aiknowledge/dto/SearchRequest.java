package com.enterprise.aiknowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for semantic search endpoint (POST /api/search).
 *
 * @param query Natural-language search query submitted by the user
 * @param topK  Optional number of top similar document chunks to retrieve
 */
public record SearchRequest(
        @NotBlank(message = "Query cannot be blank")
        @Size(max = 1000, message = "Query cannot exceed 1000 characters")
        String query,

        Integer topK
) {}
