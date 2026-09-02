package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.service.SemanticSearchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing semantic vector similarity search API.
 *
 * <p><strong>Endpoint:</strong> {@code POST /api/search}</p>
 * <p><strong>Access:</strong> Authenticated users (USER or ADMIN).</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SemanticSearchService semanticSearchService;

    public SearchController(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    /**
     * Executes a semantic search query against the user's accessible documents.
     *
     * @param request        search request with natural language query and optional topK
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

        SearchResponse response = semanticSearchService.search(request, currentUserEmail, isAdmin);
        return ResponseEntity.ok(response);
    }
}
