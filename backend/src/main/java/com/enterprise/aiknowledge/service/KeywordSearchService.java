package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.CandidateResult;
import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;

import java.util.List;

/**
 * Service interface for lexical / keyword full-text search against PostgreSQL.
 */
public interface KeywordSearchService {

    /**
     * Retrieves ranked keyword candidate matches without hydrating chunk text from the database.
     * Used by {@link HybridSearchService} to retrieve lexical candidates for Reciprocal Rank Fusion.
     *
     * @param query          natural language query
     * @param candidateLimit maximum number of candidate matches to retrieve
     * @param targetOwnerId  optional owner ID to filter results (null for ADMIN cross-tenant search)
     * @return 1-ranked list of keyword candidate matches
     */
    List<CandidateResult> retrieveCandidates(String query, int candidateLimit, Long targetOwnerId);

    /**
     * Executes standalone keyword search and hydrates chunk text from PostgreSQL.
     *
     * @param request          search request containing query and optional topK
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return search response containing ranked relevant document chunks
     */
    SearchResponse search(SearchRequest request, String currentUserEmail, boolean isAdmin);

    /**
     * Returns the default topK parameter when not explicitly specified.
     */
    int getDefaultTopK();

    /**
     * Returns the maximum allowable topK parameter.
     */
    int getMaxTopK();
}
