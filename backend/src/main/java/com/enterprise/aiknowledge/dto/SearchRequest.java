package com.enterprise.aiknowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for search endpoint (POST /api/search).
 *
 * @param query  Natural-language search query submitted by the user
 * @param topK   Optional number of top similar document chunks to retrieve
 * @param mode   Optional retrieval mode (SEMANTIC, KEYWORD, HYBRID); defaults to SEMANTIC
 * @param rerank Optional flag to activate query-to-chunk reranking (applicable in HYBRID mode)
 */
public record SearchRequest(
        @NotBlank(message = "Query cannot be blank")
        @Size(max = 1000, message = "Query cannot exceed 1000 characters")
        String query,

        Integer topK,

        SearchMode mode,

        Boolean rerank
) {
    public SearchRequest(String query, Integer topK) {
        this(query, topK, SearchMode.SEMANTIC, null);
    }

    public SearchRequest(String query, Integer topK, SearchMode mode) {
        this(query, topK, mode, null);
    }
}
