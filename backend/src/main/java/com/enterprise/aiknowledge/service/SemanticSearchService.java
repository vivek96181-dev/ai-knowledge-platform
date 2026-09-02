package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.dto.SearchResult;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service orchestrating semantic vector similarity search.
 *
 * <p><strong>Search Flow:</strong>
 * <ol>
 *   <li>Validate request parameters (query non-blank, max length 1000, 1 &le; topK &le; maxTopK).</li>
 *   <li>Resolve authenticated user identity and enforce multi-tenant authorization (USER filtered by ownerId, ADMIN cross-document).</li>
 *   <li>Generate high-dimensional (768-dim) query embedding via {@link EmbeddingService}.</li>
 *   <li>Query nearest neighbors from {@link VectorStoreService} (Qdrant).</li>
 *   <li>Batch-fetch chunk entities with their parent documents from PostgreSQL (eliminating N+1 queries).</li>
 *   <li>Preserve Qdrant relevance score ranking while skipping any stale points and enforcing ownership checks.</li>
 *   <li>Return structured {@link SearchResponse} without exposing raw vectors or internal filesystem paths.</li>
 * </ol>
 * </p>
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository documentChunkRepository;
    private final UserRepository userRepository;
    private final int defaultTopK;
    private final int maxTopK;

    @Autowired
    public SemanticSearchService(
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            DocumentChunkRepository documentChunkRepository,
            UserRepository userRepository,
            @Value("${search.default-top-k:5}") int defaultTopK,
            @Value("${search.max-top-k:20}") int maxTopK) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
    }

    /**
     * Executes semantic search for an authenticated user.
     *
     * @param request          search request containing query and optional topK
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return structured search response containing ranked search results
     */
    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest request, String currentUserEmail, boolean isAdmin) {
        // Step 1: Input validation
        if (request == null || request.query() == null || request.query().trim().isBlank()) {
            throw new IllegalArgumentException("Search query cannot be blank");
        }

        String trimmedQuery = request.query().trim();
        if (trimmedQuery.length() > 1000) {
            throw new IllegalArgumentException("Search query cannot exceed 1000 characters");
        }

        int resolvedTopK = (request.topK() != null) ? request.topK() : defaultTopK;
        if (resolvedTopK < 1 || resolvedTopK > maxTopK) {
            throw new IllegalArgumentException(String.format(
                    "topK must be between 1 and %d, but was: %d", maxTopK, resolvedTopK));
        }

        // Step 2: Resolve user identity
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        Long targetOwnerId = isAdmin ? null : currentUser.getId();

        log.info("Executing semantic search (user: {}, role: {}, targetOwnerId: {}, topK: {})",
                currentUserEmail, isAdmin ? "ADMIN" : "USER", targetOwnerId, resolvedTopK);

        // Step 3: Generate query embedding vector (768 dimensions)
        List<Float> queryVector = embeddingService.generateEmbedding(trimmedQuery);

        // Step 4: Perform vector similarity search in Qdrant
        List<ScoredChunkDto> scoredPoints = vectorStoreService.search(queryVector, resolvedTopK, targetOwnerId);
        if (scoredPoints.isEmpty()) {
            log.info("Semantic search returned 0 vector matches from Qdrant");
            return new SearchResponse(trimmedQuery, Collections.emptyList());
        }

        // Step 5: Batch-fetch chunk entities from PostgreSQL (preserves DB source-of-truth)
        List<Long> chunkIds = scoredPoints.stream().map(ScoredChunkDto::chunkId).toList();
        List<DocumentChunk> chunks = documentChunkRepository.findAllWithDocumentAndOwnerByIdIn(chunkIds);
        Map<Long, DocumentChunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));

        // Step 6: Assemble results preserving Qdrant's relevance score ranking
        List<SearchResult> results = new ArrayList<>();
        for (ScoredChunkDto scored : scoredPoints) {
            DocumentChunk chunk = chunkMap.get(scored.chunkId());

            // Handle stale Qdrant points missing from PostgreSQL
            if (chunk == null) {
                log.warn("Stale Qdrant vector detected: chunk ID {} not found in PostgreSQL. Safely skipping.",
                        scored.chunkId());
                continue;
            }

            // Defense-in-depth server-side ownership verification
            if (!isAdmin && !chunk.getDocument().getOwner().getId().equals(currentUser.getId())) {
                log.warn("Security violation: chunk ID {} owned by user {} attempted access by user {}. Safely skipping.",
                        chunk.getId(), chunk.getDocument().getOwner().getId(), currentUser.getId());
                continue;
            }

            results.add(new SearchResult(
                    chunk.getDocument().getId(),
                    chunk.getId(),
                    chunk.getPageNumber(),
                    chunk.getChunkIndex(),
                    scored.score(),
                    chunk.getText()
            ));
        }

        log.info("Semantic search completed: {} Qdrant matches -> {} final results returned",
                scoredPoints.size(), results.size());

        return new SearchResponse(trimmedQuery, results);
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public int getMaxTopK() {
        return maxTopK;
    }
}
