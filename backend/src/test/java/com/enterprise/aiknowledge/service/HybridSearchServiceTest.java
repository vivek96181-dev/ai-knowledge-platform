package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.exception.RerankerException;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HybridSearchService} covering input validation, candidate retrieval,
 * RRF fusion orchestration, multi-tenant isolation, ADMIN cross-tenant search, batch hydration,
 * reranking integration, fallback behavior, and fail-closed error handling.
 */
class HybridSearchServiceTest {

    private SemanticSearchService mockSemanticSearchService;
    private KeywordSearchService mockKeywordSearchService;
    private ReciprocalRankFuser reciprocalRankFuser;
    private DocumentChunkRepository mockChunkRepository;
    private UserRepository mockUserRepository;
    private Reranker mockReranker;

    private HybridSearchService hybridSearchService;

    private User userA;
    private User userB;
    private User admin;

    private Document docA;
    private Document docB;

    @BeforeEach
    void setUp() throws Exception {
        mockSemanticSearchService = mock(SemanticSearchService.class);
        mockKeywordSearchService = mock(KeywordSearchService.class);
        reciprocalRankFuser = new ReciprocalRankFuser();
        mockChunkRepository = mock(DocumentChunkRepository.class);
        mockUserRepository = mock(UserRepository.class);
        mockReranker = mock(Reranker.class);

        when(mockReranker.isEnabled()).thenReturn(true);
        when(mockReranker.getModelName()).thenReturn("gemini-2.5-flash");

        hybridSearchService = new HybridSearchService(
                mockSemanticSearchService,
                mockKeywordSearchService,
                reciprocalRankFuser,
                mockChunkRepository,
                mockUserRepository,
                mockReranker,
                5,
                20,
                60,
                1.0,
                1.0,
                20,
                20,
                true,
                20,
                true
        );

        userA = new User();
        setEntityId(userA, 10L);
        userA.setEmail("user_a@example.com");
        userA.setRole(Role.USER);

        userB = new User();
        setEntityId(userB, 20L);
        userB.setEmail("user_b@example.com");
        userB.setRole(Role.USER);

        admin = new User();
        setEntityId(admin, 1L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);

        docA = new Document();
        setEntityId(docA, 100L);
        docA.setOwner(userA);

        docB = new Document();
        setEntityId(docB, 200L);
        docB.setOwner(userB);

        when(mockUserRepository.findByEmail("user_a@example.com")).thenReturn(Optional.of(userA));
        when(mockUserRepository.findByEmail("user_b@example.com")).thenReturn(Optional.of(userB));
        when(mockUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    }

    // =========================================================================
    // 1. Validation Tests
    // =========================================================================

    @Test
    @DisplayName("Blank query throws IllegalArgumentException")
    void blankQueryThrowsException() {
        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("   ", 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query cannot be blank");
    }

    @Test
    @DisplayName("Query exceeding 1000 characters throws IllegalArgumentException")
    void queryExceedingMaxLengthThrowsException() {
        String longQuery = "a".repeat(1001);
        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest(longQuery, 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query cannot exceed 1000 characters");
    }

    @Test
    @DisplayName("Invalid topK throws IllegalArgumentException")
    void invalidTopKThrowsException() {
        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("query", 0, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");

        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("query", 25, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");
    }

    // =========================================================================
    // 2. Candidate Retrieval & Multi-Tenant Authorization Tests
    // =========================================================================

    @Test
    @DisplayName("USER search enforces tenant owner ID constraint in retrieval calls")
    void userSearchEnforcesTargetOwnerId() throws Exception {
        when(mockSemanticSearchService.retrieveCandidates(eq("annual leave"), eq(20), eq(10L)))
                .thenReturn(List.of(new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC)));
        when(mockKeywordSearchService.retrieveCandidates(eq("annual leave"), eq(20), eq(10L)))
                .thenReturn(Collections.emptyList());

        DocumentChunk chunk1 = createChunk(1L, docA, "Leave policy text");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(1L)))
                .thenReturn(List.of(chunk1));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("annual leave", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(1L);
        assertThat(response.results().get(0).rerankScore()).isNull();

        verify(mockSemanticSearchService).retrieveCandidates("annual leave", 20, 10L);
        verify(mockKeywordSearchService).retrieveCandidates("annual leave", 20, 10L);
    }

    @Test
    @DisplayName("ADMIN search passes null ownerId allowing cross-tenant document retrieval")
    void adminSearchPassesNullOwnerId() throws Exception {
        when(mockSemanticSearchService.retrieveCandidates(eq("policy"), eq(20), isNull()))
                .thenReturn(List.of(new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC)));
        when(mockKeywordSearchService.retrieveCandidates(eq("policy"), eq(20), isNull()))
                .thenReturn(List.of(new CandidateResult(2L, 200L, 0.8f, 1, RetrievalSourceType.KEYWORD)));

        DocumentChunk chunk1 = createChunk(1L, docA, "User A chunk");
        DocumentChunk chunk2 = createChunk(2L, docB, "User B chunk");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1, chunk2));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("policy", 5, SearchMode.HYBRID), "admin@example.com", true);

        assertThat(response.results()).hasSize(2);
        verify(mockSemanticSearchService).retrieveCandidates("policy", 20, null);
        verify(mockKeywordSearchService).retrieveCandidates("policy", 20, null);
    }

    // =========================================================================
    // 3. Reranker Integration Tests
    // =========================================================================

    @Test
    @DisplayName("rerank=true expands candidate pool, invokes Reranker, and returns reordered top-K with rerankScore")
    void rerankTrueInvokesRerankerAndReorders() throws Exception {
        // Setup 3 candidates from retrieval
        CandidateResult sem1 = new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC);
        CandidateResult sem2 = new CandidateResult(2L, 100L, 0.8f, 2, RetrievalSourceType.SEMANTIC);
        CandidateResult sem3 = new CandidateResult(3L, 100L, 0.7f, 3, RetrievalSourceType.SEMANTIC);

        when(mockSemanticSearchService.retrieveCandidates(eq("query"), eq(20), eq(10L)))
                .thenReturn(List.of(sem1, sem2, sem3));
        when(mockKeywordSearchService.retrieveCandidates(eq("query"), eq(20), eq(10L)))
                .thenReturn(Collections.emptyList());

        DocumentChunk chunk1 = createChunk(1L, docA, "Chunk 1 text");
        DocumentChunk chunk2 = createChunk(2L, docA, "Chunk 2 text");
        DocumentChunk chunk3 = createChunk(3L, docA, "Chunk 3 text");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1, chunk2, chunk3));

        // Reranker inverts the order: chunk 3 gets highest relevance (0.95), chunk 1 gets (0.60)
        when(mockReranker.rerank(eq("query"), anyList(), eq(2)))
                .thenAnswer(inv -> {
                    List<RerankCandidate> cands = inv.getArgument(1);
                    return List.of(
                            new RerankedCandidate(cands.get(2), 0.95f), // chunk 3
                            new RerankedCandidate(cands.get(0), 0.60f)  // chunk 1
                    );
                });

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 2, SearchMode.HYBRID, true), "user_a@example.com", false);

        assertThat(response.results()).hasSize(2);
        // First result must be chunk 3 due to reranking
        assertThat(response.results().get(0).chunkId()).isEqualTo(3L);
        assertThat(response.results().get(0).rerankScore()).isEqualTo(0.95f);

        // Second result must be chunk 1
        assertThat(response.results().get(1).chunkId()).isEqualTo(1L);
        assertThat(response.results().get(1).rerankScore()).isEqualTo(0.60f);

        verify(mockReranker).rerank(eq("query"), anyList(), eq(2));
    }

    @Test
    @DisplayName("rerank=true preserves multi-tenant isolation so unauthorized chunks never reach Reranker")
    void rerankPreservesMultiTenantIsolation() throws Exception {
        CandidateResult semA = new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC);
        CandidateResult semB = new CandidateResult(2L, 200L, 0.85f, 2, RetrievalSourceType.SEMANTIC); // Owned by User B

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(semA, semB));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        DocumentChunk chunkA = createChunk(1L, docA, "User A chunk");
        DocumentChunk chunkB = createChunk(2L, docB, "User B chunk (unauthorized for userA)");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunkA, chunkB));

        when(mockReranker.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(inv -> {
                    List<RerankCandidate> cands = inv.getArgument(1);
                    // Verify candidate list passed to reranker contains ONLY chunkA
                    assertThat(cands).hasSize(1);
                    assertThat(cands.get(0).chunkId()).isEqualTo(1L);
                    return List.of(new RerankedCandidate(cands.get(0), 0.90f));
                });

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID, true), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("When reranker fails and fallback is enabled, explicitly falls back to RRF order with rerankScore null")
    void rerankerFailureFallsBackToRrf() throws Exception {
        CandidateResult sem1 = new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC);
        CandidateResult sem2 = new CandidateResult(2L, 100L, 0.8f, 2, RetrievalSourceType.SEMANTIC);

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(sem1, sem2));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        DocumentChunk chunk1 = createChunk(1L, docA, "Chunk 1");
        DocumentChunk chunk2 = createChunk(2L, docA, "Chunk 2");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1, chunk2));

        when(mockReranker.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankerException("Gemini model quota exceeded"));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID, true), "user_a@example.com", false);

        assertThat(response.results()).hasSize(2);
        // Original RRF order preserved
        assertThat(response.results().get(0).chunkId()).isEqualTo(1L);
        assertThat(response.results().get(0).rerankScore()).isNull();
        assertThat(response.results().get(1).chunkId()).isEqualTo(2L);
        assertThat(response.results().get(1).rerankScore()).isNull();
    }

    @Test
    @DisplayName("When reranker fails and fallback is disabled, rethrows RerankerException")
    void rerankerFailureThrowsWhenFallbackDisabled() throws Exception {
        HybridSearchService noFallbackService = new HybridSearchService(
                mockSemanticSearchService,
                mockKeywordSearchService,
                reciprocalRankFuser,
                mockChunkRepository,
                mockUserRepository,
                mockReranker,
                5, 20, 60, 1.0, 1.0, 20, 20,
                true, 20, false // fallbackToRrf = false
        );

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC)));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        DocumentChunk chunk1 = createChunk(1L, docA, "Chunk 1");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1));

        when(mockReranker.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankerException("Model connection timeout"));

        assertThatThrownBy(() -> noFallbackService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID, true), "user_a@example.com", false))
                .isInstanceOf(RerankerException.class)
                .hasMessageContaining("Model connection timeout");
    }

    // =========================================================================
    // 4. Stale Chunks & Edge Cases
    // =========================================================================

    @Test
    @DisplayName("Stale candidate chunks missing from PostgreSQL are safely skipped")
    void staleChunksSafelySkipped() throws Exception {
        CandidateResult sem1 = new CandidateResult(1L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC);
        CandidateResult sem2 = new CandidateResult(2L, 100L, 0.8f, 2, RetrievalSourceType.SEMANTIC);

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(sem1, sem2));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        // Only chunk 1 exists in DB; chunk 2 was deleted
        DocumentChunk chunk1 = createChunk(1L, docA, "Active chunk");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Both retrieval subsystems returning zero results yields empty response")
    void bothSourcesReturnZeroYieldsEmptyResponse() {
        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).isEmpty();
        verify(mockChunkRepository, never()).findAllWithDocumentAndOwnerByIdIn(anyCollection());
    }

    // =========================================================================
    // 5. Fail-Closed Error Handling
    // =========================================================================

    @Test
    @DisplayName("Semantic retrieval failure propagates exception (fail-closed)")
    void semanticFailurePropagates() {
        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Qdrant unavailable"));

        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Qdrant unavailable");
    }

    @Test
    @DisplayName("Keyword retrieval failure propagates exception (fail-closed)")
    void keywordFailurePropagates() {
        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("PostgreSQL connection error"));

        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PostgreSQL connection error");
    }

    @Test
    @DisplayName("Unknown user email throws ResourceNotFoundException")
    void unknownUserThrowsException() {
        when(mockUserRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "unknown@example.com", false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with email: unknown@example.com");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private DocumentChunk createChunk(Long id, Document doc, String text) throws Exception {
        DocumentChunk chunk = new DocumentChunk();
        setEntityId(chunk, id);
        chunk.setDocument(doc);
        chunk.setPageNumber(1);
        chunk.setChunkIndex(0);
        chunk.setText(text);
        return chunk;
    }

    private void setEntityId(Object entity, Long id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
