package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
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
 * Service orchestrating Hybrid Search combining dense vector retrieval (Qdrant)
 * and lexical full-text retrieval (PostgreSQL FTS) using Reciprocal Rank Fusion (RRF).
 *
 * <p><strong>Retrieval Architecture:</strong>
 * <ol>
 *   <li>Validate request parameters (query non-blank, max 1000 chars, topK bounds).</li>
 *   <li>Resolve authenticated user identity and enforce multi-tenant isolation.</li>
 *   <li>Retrieve bounded semantic candidates from {@link SemanticSearchService}.</li>
 *   <li>Retrieve bounded lexical candidates from {@link KeywordSearchService}.</li>
 *   <li>Fuse and re-rank both candidate sets using {@link ReciprocalRankFuser} (RRF).</li>
 *   <li>Batch-hydrate top-K document chunk entities from PostgreSQL (eliminating N+1 queries).</li>
 *   <li>Enforce defense-in-depth server-side ownership checks and skip stale chunk points.</li>
 *   <li>Return {@link SearchResponse} preserving fused ranking order with fused RRF scores.</li>
 * </ol>
 * </p>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final ReciprocalRankFuser reciprocalRankFuser;
    private final DocumentChunkRepository documentChunkRepository;
    private final UserRepository userRepository;

    private final int defaultTopK;
    private final int maxTopK;
    private final int rrfK;
    private final double semanticWeight;
    private final double keywordWeight;
    private final int semanticCandidatesLimit;
    private final int keywordCandidatesLimit;

    @Autowired
    public HybridSearchService(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            ReciprocalRankFuser reciprocalRankFuser,
            DocumentChunkRepository documentChunkRepository,
            UserRepository userRepository,
            @Value("${search.default-top-k:5}") int defaultTopK,
            @Value("${search.max-top-k:20}") int maxTopK,
            @Value("${search.hybrid.rrf-k:60}") int rrfK,
            @Value("${search.hybrid.semantic-weight:1.0}") double semanticWeight,
            @Value("${search.hybrid.keyword-weight:1.0}") double keywordWeight,
            @Value("${search.hybrid.semantic-candidates:20}") int semanticCandidatesLimit,
            @Value("${search.hybrid.keyword-candidates:20}") int keywordCandidatesLimit) {
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.reciprocalRankFuser = reciprocalRankFuser;
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
        this.rrfK = rrfK;
        this.semanticWeight = semanticWeight;
        this.keywordWeight = keywordWeight;
        this.semanticCandidatesLimit = semanticCandidatesLimit;
        this.keywordCandidatesLimit = keywordCandidatesLimit;
    }

    /**
     * Executes hybrid search combining Qdrant vector retrieval and PostgreSQL full-text search.
     *
     * @param request          search request containing query and optional topK
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return search response containing RRF-ranked relevant document chunks
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

        log.info("Executing hybrid search (user: {}, role: {}, targetOwnerId: {}, topK: {})",
                currentUserEmail, isAdmin ? "ADMIN" : "USER", targetOwnerId, resolvedTopK);

        // Step 3 & 4: Retrieve bounded candidates from both retrieval subsystems
        int semLimit = Math.max(resolvedTopK, semanticCandidatesLimit);
        int kwLimit = Math.max(resolvedTopK, keywordCandidatesLimit);

        // Fail-closed design: underlying exceptions propagate naturally to prevent degraded or false results
        List<CandidateResult> semanticCandidates = semanticSearchService.retrieveCandidates(trimmedQuery, semLimit, targetOwnerId);
        List<CandidateResult> keywordCandidates = keywordSearchService.retrieveCandidates(trimmedQuery, kwLimit, targetOwnerId);

        log.info("Hybrid search candidate retrieval completed: {} semantic, {} keyword candidates",
                semanticCandidates != null ? semanticCandidates.size() : 0,
                keywordCandidates != null ? keywordCandidates.size() : 0);

        // Step 5: Reciprocal Rank Fusion
        List<ReciprocalRankFuser.FusedCandidate> fusedCandidates = reciprocalRankFuser.fuse(
                semanticCandidates,
                keywordCandidates,
                rrfK,
                semanticWeight,
                keywordWeight,
                resolvedTopK
        );

        if (fusedCandidates.isEmpty()) {
            log.info("Hybrid fusion produced 0 results");
            return new SearchResponse(trimmedQuery, Collections.emptyList());
        }

        // Step 6: Batch-fetch chunk entities from PostgreSQL (preserves DB as source-of-truth)
        List<Long> chunkIds = fusedCandidates.stream()
                .map(ReciprocalRankFuser.FusedCandidate::chunkId)
                .toList();

        List<DocumentChunk> chunks = documentChunkRepository.findAllWithDocumentAndOwnerByIdIn(chunkIds);
        Map<Long, DocumentChunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));

        // Step 7: Assemble results preserving RRF score ranking order
        List<SearchResult> results = new ArrayList<>(fusedCandidates.size());
        for (ReciprocalRankFuser.FusedCandidate fused : fusedCandidates) {
            DocumentChunk chunk = chunkMap.get(fused.chunkId());

            // Handle stale candidates missing from database
            if (chunk == null) {
                log.warn("Stale candidate point detected: chunk ID {} not found in database. Safely skipping.",
                        fused.chunkId());
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
                    fused.fusedScore(),
                    chunk.getText()
            ));
        }

        log.info("Hybrid search completed: {} fused candidates -> {} final hydrated results",
                fusedCandidates.size(), results.size());

        return new SearchResponse(trimmedQuery, results);
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public int getMaxTopK() {
        return maxTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public double getSemanticWeight() {
        return semanticWeight;
    }

    public double getKeywordWeight() {
        return keywordWeight;
    }
}
