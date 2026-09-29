package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.RagRequest;
import com.enterprise.aiknowledge.dto.RagResponse;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.CacheKeyFactory;
import com.enterprise.aiknowledge.service.CacheService;
import com.enterprise.aiknowledge.service.RagService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * REST controller exposing the Retrieval-Augmented Generation (RAG) question-answering endpoint.
 *
 * <p><strong>Endpoint:</strong> {@code POST /api/rag/ask}</p>
 *
 * <p><strong>Caching:</strong> RAG responses are cached in Redis (when enabled) using tenant-safe
 * cache keys derived from the authenticated principal. Cache hits bypass the full retrieval and
 * generation pipeline. Only successful, authorized responses are cached.</p>
 *
 * <p><strong>Access:</strong> Authenticated users (USER or ADMIN).</p>
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final CacheService cacheService;
    private final CacheKeyFactory cacheKeyFactory;
    private final UserRepository userRepository;
    private final long ragTtlSeconds;
    private final int defaultTopK;

    @Autowired
    public RagController(
            RagService ragService,
            CacheService cacheService,
            CacheKeyFactory cacheKeyFactory,
            UserRepository userRepository,
            @Value("${cache.rag.ttl-seconds:600}") long ragTtlSeconds,
            @Value("${search.default-top-k:5}") int defaultTopK) {
        this.ragService = ragService;
        this.cacheService = cacheService;
        this.cacheKeyFactory = cacheKeyFactory;
        this.userRepository = userRepository;
        this.ragTtlSeconds = ragTtlSeconds;
        this.defaultTopK = defaultTopK;
    }

    /**
     * Answers a natural-language question grounded strictly in enterprise document knowledge.
     *
     * @param request        RAG request containing user query and optional topK
     * @param authentication current user authentication principal
     * @return RAG response containing grounded answer and sources
     */
    @PostMapping("/ask")
    public ResponseEntity<RagResponse> ask(
            @Valid @RequestBody RagRequest request,
            Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        int resolvedTopK = (request.topK() != null) ? request.topK() : defaultTopK;

        // Cache-aside: attempt cache read
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildRagKey(userId, isAdmin, request.query(), resolvedTopK);

            Optional<RagResponse> cached = cacheService.get(cacheKey, RagResponse.class);
            if (cached.isPresent()) {
                return ResponseEntity.ok(cached.get());
            }
        }

        // Cache miss: execute RAG pipeline
        RagResponse response = ragService.ask(request, currentUserEmail, isAdmin);

        // Cache the authorized result
        if (cacheService.isEnabled()) {
            Long userId = resolveUserId(currentUserEmail);
            String cacheKey = cacheKeyFactory.buildRagKey(userId, isAdmin, request.query(), resolvedTopK);
            cacheService.put(cacheKey, response, ragTtlSeconds);
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
