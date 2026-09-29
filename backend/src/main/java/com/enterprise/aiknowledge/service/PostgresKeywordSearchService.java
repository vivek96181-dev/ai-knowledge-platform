package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.ChunkKeywordMatch;
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
 * Production implementation of {@link KeywordSearchService} using PostgreSQL Full-Text Search (FTS).
 *
 * <p><strong>Search Strategy:</strong>
 * <ul>
 *   <li>Evaluates natural-language user queries using PostgreSQL {@code websearch_to_tsquery}.</li>
 *   <li>Matches against {@code to_tsvector(language, text)} backed by a GIN expression index.</li>
 *   <li>Ranks matches by cover-density score using {@code ts_rank_cd} in descending order.</li>
 *   <li>Enforces multi-tenant ownership filtering strictly in SQL and in memory defense-in-depth.</li>
 *   <li>Configurable FTS language (default {@code english}).</li>
 * </ul>
 * </p>
 */
@Service
public class PostgresKeywordSearchService implements KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(PostgresKeywordSearchService.class);

    private final DocumentChunkRepository documentChunkRepository;
    private final UserRepository userRepository;
    private final String ftsLanguage;
    private final int defaultTopK;
    private final int maxTopK;

    @Autowired
    public PostgresKeywordSearchService(
            DocumentChunkRepository documentChunkRepository,
            UserRepository userRepository,
            @Value("${search.fts.language:english}") String ftsLanguage,
            @Value("${search.default-top-k:5}") int defaultTopK,
            @Value("${search.max-top-k:20}") int maxTopK) {
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
        this.ftsLanguage = ftsLanguage;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateResult> retrieveCandidates(String query, int candidateLimit, Long targetOwnerId) {
        if (query == null || query.isBlank() || candidateLimit <= 0) {
            return Collections.emptyList();
        }

        String trimmedQuery = query.trim();
        List<ChunkKeywordMatch> matches;
        if (targetOwnerId != null) {
            matches = documentChunkRepository.searchKeywordByOwner(trimmedQuery, ftsLanguage, targetOwnerId, candidateLimit);
        } else {
            matches = documentChunkRepository.searchKeywordAll(trimmedQuery, ftsLanguage, candidateLimit);
        }

        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        List<CandidateResult> candidates = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            ChunkKeywordMatch match = matches.get(i);
            float score = (match.getScore() != null) ? match.getScore() : 0.0f;
            candidates.add(new CandidateResult(
                    match.getChunkId(),
                    match.getDocumentId(),
                    score,
                    i + 1, // 1-based rank
                    RetrievalSourceType.KEYWORD
            ));
        }

        return candidates;
    }

    @Override
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

        log.info("Executing keyword search (user: {}, role: {}, targetOwnerId: {}, topK: {})",
                currentUserEmail, isAdmin ? "ADMIN" : "USER", targetOwnerId, resolvedTopK);

        // Step 3: Retrieve keyword candidates
        List<CandidateResult> candidates = retrieveCandidates(trimmedQuery, resolvedTopK, targetOwnerId);
        if (candidates.isEmpty()) {
            log.info("Keyword search returned 0 matches from PostgreSQL FTS");
            return new SearchResponse(trimmedQuery, Collections.emptyList());
        }

        // Step 4: Batch-fetch chunk entities from PostgreSQL
        List<Long> chunkIds = candidates.stream().map(CandidateResult::chunkId).toList();
        List<DocumentChunk> chunks = documentChunkRepository.findAllWithDocumentAndOwnerByIdIn(chunkIds);
        Map<Long, DocumentChunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));

        // Step 5: Assemble results preserving keyword ranking order
        List<SearchResult> results = new ArrayList<>(candidates.size());
        for (CandidateResult candidate : candidates) {
            DocumentChunk chunk = chunkMap.get(candidate.chunkId());

            if (chunk == null) {
                log.warn("Stale chunk ID {} in keyword search not found in database. Safely skipping.",
                        candidate.chunkId());
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
                    candidate.score(),
                    chunk.getText()
            ));
        }

        log.info("Keyword search completed: {} FTS matches -> {} final results returned",
                candidates.size(), results.size());

        return new SearchResponse(trimmedQuery, results);
    }

    @Override
    public int getDefaultTopK() {
        return defaultTopK;
    }

    @Override
    public int getMaxTopK() {
        return maxTopK;
    }
}
