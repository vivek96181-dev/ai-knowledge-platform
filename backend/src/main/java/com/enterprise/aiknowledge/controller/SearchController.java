package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.SearchMode;
import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.service.HybridSearchService;
import com.enterprise.aiknowledge.service.KeywordSearchService;
import com.enterprise.aiknowledge.service.SemanticSearchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing document search APIs (Semantic Vector, Keyword FTS, and Hybrid RRF).
 *
 * <p><strong>Endpoints:</strong>
 * <ul>
 *   <li>{@code POST /api/search}: Unified search endpoint supporting {@code mode} (SEMANTIC, KEYWORD, HYBRID). Defaults to SEMANTIC.</li>
 *   <li>{@code POST /api/search/hybrid}: Direct convenience endpoint for hybrid RRF search.</li>
 * </ul>
 * </p>
 * <p><strong>Access:</strong> Authenticated users (USER or ADMIN).</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final HybridSearchService hybridSearchService;

    public SearchController(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            HybridSearchService hybridSearchService) {
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * Executes a search query against the user's accessible documents.
     * Default mode is {@link SearchMode#SEMANTIC} for backward compatibility.
     *
     * @param request        search request with query, optional topK, and optional mode
     * @param authentication current user authentication principal
     * @return search response containing ranked relevant document chunks
     */
    @PostMapping
    public ResponseEntity<SearchResponse> search(
            @Valid @RequestBody SearchRequest request,
            Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        SearchMode mode = (request.mode() != null) ? request.mode() : SearchMode.SEMANTIC;

        SearchResponse response = switch (mode) {
            case SEMANTIC -> semanticSearchService.search(request, currentUserEmail, isAdmin);
            case KEYWORD -> keywordSearchService.search(request, currentUserEmail, isAdmin);
            case HYBRID -> hybridSearchService.search(request, currentUserEmail, isAdmin);
        };

        return ResponseEntity.ok(response);
    }

    /**
     * Dedicated endpoint for hybrid search fusing Qdrant semantic vector similarity
     * and PostgreSQL lexical full-text search via Reciprocal Rank Fusion (RRF).
     *
     * @param request        search request with natural language query and optional topK
     * @param authentication current user authentication principal
     * @return search response containing RRF-ranked relevant document chunks
     */
    @PostMapping("/hybrid")
    public ResponseEntity<SearchResponse> searchHybrid(
            @Valid @RequestBody SearchRequest request,
            Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        SearchResponse response = hybridSearchService.search(request, currentUserEmail, isAdmin);
        return ResponseEntity.ok(response);
    }
}
