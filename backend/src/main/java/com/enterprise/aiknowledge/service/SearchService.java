package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;

/**
 * Service interface for semantic vector similarity search.
 */
public interface SearchService {

    /**
     * Executes semantic vector search against authorized documents.
     *
     * @param request          search request containing query and optional topK
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return search response containing ranked relevant document chunks
     */
    SearchResponse search(SearchRequest request, String currentUserEmail, boolean isAdmin);

    /**
     * Returns the default topK parameter when not explicitly specified in the request.
     */
    int getDefaultTopK();

    /**
     * Returns the maximum allowable topK parameter.
     */
    int getMaxTopK();
}
