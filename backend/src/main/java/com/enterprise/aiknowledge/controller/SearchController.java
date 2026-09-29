package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.SearchMode;
import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.CacheKeyFactory;
import com.enterprise.aiknowledge.service.CacheService;
import com.enterprise.aiknowledge.service.HybridSearchService;
import com.enterprise.aiknowledge.service.KeywordSearchService;
import com.enterprise.aiknowledge.service.SemanticSearchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * REST controller exposing document search APIs (Semantic Vector, Keyword FTS, Hybrid RRF, and Relevance Reranking).
 *
 * <p><strong>Endpoints:</strong>
 * <ul>
 *   <li>{@code POST /api/search}: Unified search endpoint supporting {@code mode} (SEMANTIC, KEYWORD, HYBRID) and optional {@code rerank}. Defaults to SEMANTIC.</li>
 *   <li>{@code POST /api/search/hybrid}: Direct convenience endpoint for hybrid RRF search with optional {@code rerank}.</li>
 * </ul>
 * </p>
 *
 * <p><strong>Caching:</strong> Search results are cached in Redis (when enabled) using tenant-safe
 * cache keys derived from the authenticated principal. Cache hits bypass the full search pipeline.
 * Only successful, authorized responses are cached. Failures and errors are never cached.</p>
 *
 * <p><strong>Access:</strong> Authenticated users (USER or ADMIN).</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final HybridSearchService hybridSearchService;
    private final CacheService cacheService;
    private final CacheKeyFactory cacheKeyFactory;
    private final UserRepository userRepository;
    private final long searchTtlSeconds;
    private final int defaultTopK;

    public SearchController(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            HybridSearchService hybridSearchService,
            CacheService cacheService,
            CacheKeyFactory cacheKeyFactory,
            UserRepository userRepository,
            @Value("${cache.search.ttl-seconds:300}") long searchTtlSeconds,
            @Value("${search.default-top-k:5}") int defaultTopK) {
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.hybridSearchService = hybridSearchService;
        this.cacheService = cacheService;
        this.cacheKeyFactory = cacheKeyFactory;
        this.userRepository = userRepository;
        this.searchTtlSeconds = searchTtlSeconds;
        this.defaultTopK = defaultTopK;
    }

    /**
     * Executes a search query against the user's accessible documents.
     * Default mode is {@link SearchMode#SEMANTIC} for backward compatibility.
     *
     * @param request        search request with query, optional topK, optional mode, and optional rerank
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
        int resolvedTopK = (request.topK() != null) ? request.topK() : defaultTopK;
        boolean rerank = Boolean.TRUE.equals(request.rerank());

        // Cache-aside: attempt cache read
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildSearchKey(
                    userId, isAdmin, request.query(), mode.name(), resolvedTopK, rerank);

            Optional<SearchResponse> cached = cacheService.get(cacheKey, SearchResponse.class);
            if (cached.isPresent()) {
                return ResponseEntity.ok(cached.get());
            }
        }

        // Cache miss: execute search pipeline
        SearchResponse response = switch (mode) {
            case SEMANTIC -> semanticSearchService.search(request, currentUserEmail, isAdmin);
            case KEYWORD -> keywordSearchService.search(request, currentUserEmail, isAdmin);
            case HYBRID -> hybridSearchService.search(request, currentUserEmail, isAdmin);
        };

        // Cache the authorized result
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildSearchKey(
                    userId, isAdmin, request.query(), mode.name(), resolvedTopK, rerank);
            cacheService.put(cacheKey, response, searchTtlSeconds);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Dedicated endpoint for hybrid search fusing Qdrant semantic vector similarity
     * and PostgreSQL lexical full-text search via Reciprocal Rank Fusion (RRF),
     * with optional relevance reranking.
     *
     * @param request        search request with natural language query, optional topK, and optional rerank
     * @param authentication current user authentication principal
     * @return search response containing RRF-ranked or reranked relevant document chunks
     */
    @PostMapping("/hybrid")
    public ResponseEntity<SearchResponse> searchHybrid(
            @Valid @RequestBody SearchRequest request,
            Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        int resolvedTopK = (request.topK() != null) ? request.topK() : defaultTopK;
        boolean rerank = Boolean.TRUE.equals(request.rerank());

        // Cache-aside: attempt cache read
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildSearchKey(
                    userId, isAdmin, request.query(), SearchMode.HYBRID.name(), resolvedTopK, rerank);

            Optional<SearchResponse> cached = cacheService.get(cacheKey, SearchResponse.class);
            if (cached.isPresent()) {
                return ResponseEntity.ok(cached.get());
            }
        }

        // Cache miss: execute hybrid search pipeline
        SearchResponse response = hybridSearchService.search(request, currentUserEmail, isAdmin);

        // Cache the authorized result
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildSearchKey(
                    userId, isAdmin, request.query(), SearchMode.HYBRID.name(), resolvedTopK, rerank);
            cacheService.put(cacheKey, response, searchTtlSeconds);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Resolves the authenticated user's database ID from their email.
     * This ID is used only for cache key construction — never trusted from the request body.
     */
    private Long resolveUserId(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
        return user.getId();
    }
}
