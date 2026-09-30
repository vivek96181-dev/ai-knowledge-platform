package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.exception.RerankerException;
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
 * Orchestrator service implementing Hybrid Search with optional relevance Reranking.
 *
 * <p><strong>Pipeline Execution Steps:</strong>
 * <ol>
 *   <li>Retrieve bounded semantic candidates from {@link SemanticSearchService}.</li>
 *   <li>Retrieve bounded lexical candidates from {@link KeywordSearchService}.</li>
 *   <li>Fuse both candidate sets using {@link ReciprocalRankFuser} (RRF).</li>
 *   <li>Batch-hydrate document chunk entities from PostgreSQL (zero N+1 queries).</li>
 *   <li>Enforce defense-in-depth server-side ownership checks.</li>
 *   <li>If reranking requested, score authorized candidates via {@link Reranker} and sort by relevance.</li>
 *   <li>Return {@link SearchResponse} containing ranked results with both RRF and reranker scores.</li>
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
    private final Reranker reranker;
    private final com.enterprise.aiknowledge.observability.PlatformMetrics platformMetrics;

    private final int defaultTopK;
    private final int maxTopK;
    private final int rrfK;
    private final double semanticWeight;
    private final double keywordWeight;
    private final int semanticCandidatesLimit;
    private final int keywordCandidatesLimit;

    private final boolean rerankingEnabled;
    private final int rerankCandidateCount;
    private final boolean fallbackToRrf;

    public HybridSearchService(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            ReciprocalRankFuser reciprocalRankFuser,
            DocumentChunkRepository documentChunkRepository,
            UserRepository userRepository,
            Reranker reranker,
            @Value("${search.default-top-k:5}") int defaultTopK,
            @Value("${search.max-top-k:20}") int maxTopK,
            @Value("${search.hybrid.rrf-k:60}") int rrfK,
            @Value("${search.hybrid.semantic-weight:1.0}") double semanticWeight,
            @Value("${search.hybrid.keyword-weight:1.0}") double keywordWeight,
            @Value("${search.hybrid.semantic-candidates:20}") int semanticCandidatesLimit,
            @Value("${search.hybrid.keyword-candidates:20}") int keywordCandidatesLimit,
            @Value("${search.reranking.enabled:true}") boolean rerankingEnabled,
            @Value("${search.reranking.candidate-count:20}") int rerankCandidateCount,
            @Value("${search.reranking.fallback-to-rrf:true}") boolean fallbackToRrf) {
        this(semanticSearchService, keywordSearchService, reciprocalRankFuser, documentChunkRepository,
                userRepository, reranker, defaultTopK, maxTopK, rrfK, semanticWeight, keywordWeight,
                semanticCandidatesLimit, keywordCandidatesLimit, rerankingEnabled, rerankCandidateCount, fallbackToRrf, null);
    }

    @Autowired
    public HybridSearchService(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            ReciprocalRankFuser reciprocalRankFuser,
            DocumentChunkRepository documentChunkRepository,
            UserRepository userRepository,
            Reranker reranker,
            @Value("${search.default-top-k:5}") int defaultTopK,
            @Value("${search.max-top-k:20}") int maxTopK,
            @Value("${search.hybrid.rrf-k:60}") int rrfK,
            @Value("${search.hybrid.semantic-weight:1.0}") double semanticWeight,
            @Value("${search.hybrid.keyword-weight:1.0}") double keywordWeight,
            @Value("${search.hybrid.semantic-candidates:20}") int semanticCandidatesLimit,
            @Value("${search.hybrid.keyword-candidates:20}") int keywordCandidatesLimit,
            @Value("${search.reranking.enabled:true}") boolean rerankingEnabled,
            @Value("${search.reranking.candidate-count:20}") int rerankCandidateCount,
            @Value("${search.reranking.fallback-to-rrf:true}") boolean fallbackToRrf,
            @Autowired(required = false) com.enterprise.aiknowledge.observability.PlatformMetrics platformMetrics) {
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.reciprocalRankFuser = reciprocalRankFuser;
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
        this.reranker = reranker;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
        this.rrfK = rrfK;
        this.semanticWeight = semanticWeight;
        this.keywordWeight = keywordWeight;
        this.semanticCandidatesLimit = semanticCandidatesLimit;
        this.keywordCandidatesLimit = keywordCandidatesLimit;
        this.rerankingEnabled = rerankingEnabled;
        this.rerankCandidateCount = rerankCandidateCount;
        this.fallbackToRrf = fallbackToRrf;
        this.platformMetrics = platformMetrics;
    }

    /**
     * Executes hybrid search combining Qdrant vector retrieval, PostgreSQL full-text search,
     * and optional query-to-chunk relevance reranking.
     *
     * @param request          search request containing query, optional topK, and optional rerank flag
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return search response containing ranked relevant document chunks
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

        // Determine if reranking should be performed
        boolean shouldRerank = Boolean.TRUE.equals(request.rerank())
                && rerankingEnabled
                && reranker != null
                && reranker.isEnabled();

        // Step 2: Resolve user identity
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        Long targetOwnerId = isAdmin ? null : currentUser.getId();

        log.info("Executing hybrid search (user: {}, role: {}, targetOwnerId: {}, topK: {}, rerank: {})",
                currentUserEmail, isAdmin ? "ADMIN" : "USER", targetOwnerId, resolvedTopK, shouldRerank);

        // Step 3: Determine candidate bounds
        int fusionTargetCount = shouldRerank ? Math.max(resolvedTopK, rerankCandidateCount) : resolvedTopK;
        int semLimit = Math.max(fusionTargetCount, semanticCandidatesLimit);
        int kwLimit = Math.max(fusionTargetCount, keywordCandidatesLimit);

        // Step 4: Retrieve bounded candidates from both retrieval subsystems
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
                fusionTargetCount
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

        // Step 7: Filter authorized candidates
        List<RerankCandidate> authorizedCandidates = new ArrayList<>();
        for (ReciprocalRankFuser.FusedCandidate fused : fusedCandidates) {
            DocumentChunk chunk = chunkMap.get(fused.chunkId());

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

            authorizedCandidates.add(new RerankCandidate(
                    chunk.getId(),
                    chunk.getDocument().getId(),
                    chunk.getPageNumber(),
                    chunk.getChunkIndex(),
                    fused.fusedScore(),
                    chunk.getText()
            ));
        }

        // Step 8: Apply Reranker if requested, or assemble standard RRF results
        List<SearchResult> results = new ArrayList<>();

        if (shouldRerank && !authorizedCandidates.isEmpty()) {
            if (platformMetrics != null) {
                platformMetrics.recordRerankCandidates(authorizedCandidates.size());
            }
            long startTime = System.currentTimeMillis();
            try {
                List<RerankedCandidate> reranked = reranker.rerank(trimmedQuery, authorizedCandidates, resolvedTopK);
                long latency = System.currentTimeMillis() - startTime;
                if (platformMetrics != null) {
                    platformMetrics.recordRerankRequest("success");
                    platformMetrics.recordRerankLatency("success", latency);
                }
                for (RerankedCandidate rc : reranked) {
                    RerankCandidate c = rc.candidate();
                    results.add(new SearchResult(
                            c.documentId(),
                            c.chunkId(),
                            c.pageNumber(),
                            c.chunkIndex(),
                            c.rrfScore(),
                            c.text(),
                            rc.rerankScore()
                    ));
                }
                log.info("Hybrid search with reranking completed: {} candidates -> {} final results",
                        authorizedCandidates.size(), results.size());
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - startTime;
                if (platformMetrics != null) {
                    platformMetrics.recordRerankRequest("failure");
                    platformMetrics.recordRerankLatency("failure", latency);
                    platformMetrics.recordRerankFailure(e.getClass().getSimpleName());
                }
                if (fallbackToRrf) {
                    if (platformMetrics != null) {
                        platformMetrics.recordRerankFallback();
                    }
                    log.warn("Reranking failed for query ({}). Explicitly falling back to RRF candidate ranking without rerank scores.",
                            e.getMessage());
                    // Fall back explicitly to pre-reranked RRF order truncated to topK
                    int fallbackLimit = Math.min(resolvedTopK, authorizedCandidates.size());
                    for (int i = 0; i < fallbackLimit; i++) {
                        RerankCandidate c = authorizedCandidates.get(i);
                        results.add(new SearchResult(
                                c.documentId(),
                                c.chunkId(),
                                c.pageNumber(),
                                c.chunkIndex(),
                                c.rrfScore(),
                                c.text(),
                                null // rerankScore is explicitly null to signify fallback
                        ));
                    }
                } else {
                    throw new RerankerException("Reranker failed and fallback is disabled: " + e.getMessage(), e);
                }
            }
        } else {
            // Standard un-reranked RRF path
            int finalLimit = Math.min(resolvedTopK, authorizedCandidates.size());
            for (int i = 0; i < finalLimit; i++) {
                RerankCandidate c = authorizedCandidates.get(i);
                results.add(new SearchResult(
                        c.documentId(),
                        c.chunkId(),
                        c.pageNumber(),
                        c.chunkIndex(),
                        c.rrfScore(),
                        c.text()
                ));
            }
            log.info("Standard hybrid search completed: {} fused candidates -> {} final results",
                    authorizedCandidates.size(), results.size());
        }

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

    public boolean isRerankingEnabled() {
        return rerankingEnabled;
    }

    public int getRerankCandidateCount() {
        return rerankCandidateCount;
    }

    public boolean isFallbackToRrf() {
        return fallbackToRrf;
    }
}
