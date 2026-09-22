package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
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
 * stale chunk skipping, and fail-closed error handling.
 */
class HybridSearchServiceTest {

    private SemanticSearchService mockSemanticSearchService;
    private KeywordSearchService mockKeywordSearchService;
    private ReciprocalRankFuser reciprocalRankFuser;
    private DocumentChunkRepository mockChunkRepository;
    private UserRepository mockUserRepository;

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

        hybridSearchService = new HybridSearchService(
                mockSemanticSearchService,
                mockKeywordSearchService,
                reciprocalRankFuser,
                mockChunkRepository,
                mockUserRepository,
                5,
                20,
                60,
                1.0,
                1.0,
                20,
                20
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

    private void setEntityId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private DocumentChunk createChunk(Long id, Document document, int pageNumber, int chunkIndex, String text) throws Exception {
        DocumentChunk chunk = new DocumentChunk(document, chunkIndex, pageNumber, text, 0, text.length());
        setEntityId(chunk, id);
        return chunk;
    }

    // =========================================================================
    // 1. Validation Tests
    // =========================================================================

    @Test
    @DisplayName("Blank query throws IllegalArgumentException")
    void blankQueryThrowsException() {
        assertThatThrownBy(() -> hybridSearchService.search(new SearchRequest("", 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query cannot be blank");
    }

    @Test
    @DisplayName("Query exceeding 1000 characters throws IllegalArgumentException")
    void excessiveQueryThrowsException() {
        String hugeQuery = "x".repeat(1001);
        assertThatThrownBy(() -> hybridSearchService.search(new SearchRequest(hugeQuery, 5, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 1000 characters");
    }

    @Test
    @DisplayName("Invalid topK throws IllegalArgumentException")
    void invalidTopKThrowsException() {
        assertThatThrownBy(() -> hybridSearchService.search(new SearchRequest("query", 0, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");

        assertThatThrownBy(() -> hybridSearchService.search(new SearchRequest("query", 25, SearchMode.HYBRID), "user_a@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");
    }

    // =========================================================================
    // 2. Multi-Tenant Security & Ownership Filtering
    // =========================================================================

    @Test
    @DisplayName("USER search enforces ownerId filter with caller's ID and never reintroduces foreign chunks")
    void userSearchEnforcesOwnerIdFilterAndDefenseInDepth() throws Exception {
        DocumentChunk chunkA = createChunk(101L, docA, 1, 0, "User A leave policy.");
        DocumentChunk chunkB = createChunk(202L, docB, 1, 0, "User B confidential compensation.");

        // Semantic returns chunkA (owned by User A)
        when(mockSemanticSearchService.retrieveCandidates(eq("leave policy"), anyInt(), eq(10L)))
                .thenReturn(List.of(new CandidateResult(101L, 100L, 0.95f, 1, RetrievalSourceType.SEMANTIC)));

        // Keyword returns chunkA
        when(mockKeywordSearchService.retrieveCandidates(eq("leave policy"), anyInt(), eq(10L)))
                .thenReturn(List.of(new CandidateResult(101L, 100L, 0.80f, 1, RetrievalSourceType.KEYWORD)));

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(101L)))
                .thenReturn(List.of(chunkA));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("leave policy", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(101L);
        assertThat(response.results().get(0).text()).isEqualTo("User A leave policy.");
        // Verify owner ID 10L was passed to both retrieval services
        verify(mockSemanticSearchService).retrieveCandidates(eq("leave policy"), anyInt(), eq(10L));
        verify(mockKeywordSearchService).retrieveCandidates(eq("leave policy"), anyInt(), eq(10L));
    }

    @Test
    @DisplayName("Defense-in-depth: If foreign chunk B somehow slips into fused list for User A, it is dropped")
    void defenseInDepthDropsForeignChunk() throws Exception {
        DocumentChunk chunkA = createChunk(101L, docA, 1, 0, "User A guidelines.");
        DocumentChunk chunkB = createChunk(202L, docB, 1, 0, "User B trade secrets.");

        // Simulate rogue candidate returned for User A
        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new CandidateResult(101L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC),
                        new CandidateResult(202L, 200L, 0.8f, 2, RetrievalSourceType.SEMANTIC)
                ));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(101L, 202L)))
                .thenReturn(List.of(chunkA, chunkB));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("guidelines", 5, SearchMode.HYBRID), "user_a@example.com", false);

        // chunkB must be filtered out by defense-in-depth
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(101L);
        assertThat(response.results().get(0).text()).isEqualTo("User A guidelines.");
    }

    @Test
    @DisplayName("ADMIN search passes null ownerId and retrieves chunks across all tenants")
    void adminSearchCanRetrieveAcrossTenants() throws Exception {
        DocumentChunk chunkA = createChunk(101L, docA, 1, 0, "User A guidelines.");
        DocumentChunk chunkB = createChunk(202L, docB, 1, 0, "User B compliance.");

        when(mockSemanticSearchService.retrieveCandidates(eq("guidelines"), anyInt(), isNull()))
                .thenReturn(List.of(new CandidateResult(101L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC)));
        when(mockKeywordSearchService.retrieveCandidates(eq("guidelines"), anyInt(), isNull()))
                .thenReturn(List.of(new CandidateResult(202L, 200L, 0.85f, 1, RetrievalSourceType.KEYWORD)));

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunkA, chunkB));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("guidelines", 5, SearchMode.HYBRID), "admin@example.com", true);

        assertThat(response.results()).hasSize(2);
        verify(mockSemanticSearchService).retrieveCandidates(eq("guidelines"), anyInt(), isNull());
        verify(mockKeywordSearchService).retrieveCandidates(eq("guidelines"), anyInt(), isNull());
    }

    // =========================================================================
    // 3. Batch Hydration, Ranking, and Stale Chunks
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL hydration is performed in exactly one batch query preserving fused RRF order")
    void batchHydrationPreservesRrfRanking() throws Exception {
        DocumentChunk chunk1 = createChunk(101L, docA, 1, 0, "Chunk 1 text");
        DocumentChunk chunk2 = createChunk(102L, docA, 2, 1, "Chunk 2 text");

        // Semantic: 101 (rank 1), 102 (rank 2)
        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new CandidateResult(101L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC),
                        new CandidateResult(102L, 100L, 0.7f, 2, RetrievalSourceType.SEMANTIC)
                ));
        // Keyword: 102 (rank 1) -> Chunk 102 receives boost and moves to rank 1!
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new CandidateResult(102L, 100L, 0.8f, 1, RetrievalSourceType.KEYWORD)
                ));

        // DB returns chunks in reverse order (e.g. 101, 102)
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1, chunk2));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(2);
        // Chunk 102 has score 1/62 + 1/61 ≈ 0.0325, while 101 has score 1/61 ≈ 0.0164
        assertThat(response.results().get(0).chunkId()).isEqualTo(102L);
        assertThat(response.results().get(0).text()).isEqualTo("Chunk 2 text");
        assertThat(response.results().get(1).chunkId()).isEqualTo(101L);
        assertThat(response.results().get(1).text()).isEqualTo("Chunk 1 text");

        // Exactly one batch query to repository
        verify(mockChunkRepository, times(1)).findAllWithDocumentAndOwnerByIdIn(anyCollection());
    }

    @Test
    @DisplayName("Stale candidate point missing from database is safely skipped")
    void staleCandidateSafelySkipped() throws Exception {
        DocumentChunk chunk1 = createChunk(101L, docA, 1, 0, "Valid chunk");

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new CandidateResult(999L, 100L, 0.99f, 1, RetrievalSourceType.SEMANTIC), // Stale chunk 999
                        new CandidateResult(101L, 100L, 0.85f, 2, RetrievalSourceType.SEMANTIC)
                ));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        // DB only returns 101L, 999L does not exist
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(101L);
    }

    // =========================================================================
    // 4. Empty and Zero Results Handling
    // =========================================================================

    @Test
    @DisplayName("Zero semantic results and valid keyword results returns keyword results with RRF scores")
    void zeroSemanticResultsValidKeywordResults() throws Exception {
        DocumentChunk chunk1 = createChunk(101L, docA, 1, 0, "Keyword matched chunk");

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(new CandidateResult(101L, 100L, 0.8f, 1, RetrievalSourceType.KEYWORD)));

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("Zero keyword results and valid semantic results returns semantic results with RRF scores")
    void zeroKeywordResultsValidSemanticResults() throws Exception {
        DocumentChunk chunk1 = createChunk(101L, docA, 1, 0, "Semantic matched chunk");

        when(mockSemanticSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(List.of(new CandidateResult(101L, 100L, 0.9f, 1, RetrievalSourceType.SEMANTIC)));
        when(mockKeywordSearchService.retrieveCandidates(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(anyCollection()))
                .thenReturn(List.of(chunk1));

        SearchResponse response = hybridSearchService.search(
                new SearchRequest("query", 5, SearchMode.HYBRID), "user_a@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(101L);
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
}
