package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.dto.SearchResult;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SemanticSearchService} covering input validation, query embedding,
 * Qdrant vector search invocation, owner-based authorization, batch PostgreSQL retrieval,
 * ranking preservation, stale point handling, and security invariants.
 */
class SemanticSearchServiceTest {

    private EmbeddingService mockEmbeddingService;
    private VectorStoreService mockVectorStoreService;
    private DocumentChunkRepository mockChunkRepository;
    private UserRepository mockUserRepository;

    private SemanticSearchService searchService;

    private User testUser;
    private User adminUser;
    private User otherUser;

    private Document testDoc;
    private Document otherDoc;

    private final List<Float> mockQueryVector = Collections.nCopies(768, 0.05f);

    @BeforeEach
    void setUp() throws Exception {
        mockEmbeddingService = mock(EmbeddingService.class);
        mockVectorStoreService = mock(VectorStoreService.class);
        mockChunkRepository = mock(DocumentChunkRepository.class);
        mockUserRepository = mock(UserRepository.class);

        searchService = new SemanticSearchService(
                mockEmbeddingService,
                mockVectorStoreService,
                mockChunkRepository,
                mockUserRepository,
                5,
                20
        );

        testUser = new User();
        setEntityId(testUser, 10L);
        testUser.setEmail("user@example.com");
        testUser.setRole(Role.USER);

        adminUser = new User();
        setEntityId(adminUser, 1L);
        adminUser.setEmail("admin@example.com");
        adminUser.setRole(Role.ADMIN);

        otherUser = new User();
        setEntityId(otherUser, 99L);
        otherUser.setEmail("other@example.com");
        otherUser.setRole(Role.USER);

        testDoc = new Document();
        setEntityId(testDoc, 100L);
        testDoc.setOwner(testUser);

        otherDoc = new Document();
        setEntityId(otherDoc, 200L);
        otherDoc.setOwner(otherUser);

        when(mockUserRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(mockUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(mockEmbeddingService.generateEmbedding(anyString())).thenReturn(mockQueryVector);
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
        assertThatThrownBy(() -> searchService.search(new SearchRequest("", 5), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query cannot be blank");

        assertThatThrownBy(() -> searchService.search(new SearchRequest("   ", 5), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query cannot be blank");
    }

    @Test
    @DisplayName("Null search request throws IllegalArgumentException")
    void nullRequestThrowsException() {
        assertThatThrownBy(() -> searchService.search(null, "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query cannot be blank");
    }

    @Test
    @DisplayName("Query exceeding 1000 characters throws IllegalArgumentException")
    void excessiveQueryLengthThrowsException() {
        String hugeQuery = "a".repeat(1001);
        assertThatThrownBy(() -> searchService.search(new SearchRequest(hugeQuery, 5), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 1000 characters");
    }

    @Test
    @DisplayName("Invalid topK (< 1 or > maxTopK) throws IllegalArgumentException")
    void invalidTopKThrowsException() {
        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", 0), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");

        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", -5), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");

        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", 21), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be between 1 and 20");
    }

    // =========================================================================
    // 2. Default topK & Query Embedding Invocation
    // =========================================================================

    @Test
    @DisplayName("Default topK (5) is used when topK is null in request")
    void defaultTopKIsUsedWhenNull() {
        when(mockVectorStoreService.search(anyList(), anyInt(), any())).thenReturn(Collections.emptyList());

        SearchResponse response = searchService.search(new SearchRequest("leave policy", null), "user@example.com", false);

        verify(mockVectorStoreService).search(eq(mockQueryVector), eq(5), eq(10L));
        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("Query embedding is generated with trimmed query text")
    void queryEmbeddingGeneratedWithTrimmedText() {
        when(mockVectorStoreService.search(anyList(), anyInt(), any())).thenReturn(Collections.emptyList());

        searchService.search(new SearchRequest("   company leave policy   ", 5), "user@example.com", false);

        verify(mockEmbeddingService).generateEmbedding("company leave policy");
    }

    // =========================================================================
    // 3. Multi-Tenant Ownership Filtering (USER vs ADMIN)
    // =========================================================================

    @Test
    @DisplayName("USER search enforces ownerId filter with caller's user ID")
    void userSearchEnforcesOwnerIdFilter() {
        when(mockVectorStoreService.search(anyList(), anyInt(), any())).thenReturn(Collections.emptyList());

        searchService.search(new SearchRequest("leave policy", 5), "user@example.com", false);

        verify(mockVectorStoreService).search(anyList(), eq(5), eq(10L));
    }

    @Test
    @DisplayName("ADMIN search passes null ownerId filter to search across all documents")
    void adminSearchPassesNullOwnerIdFilter() {
        when(mockVectorStoreService.search(anyList(), anyInt(), any())).thenReturn(Collections.emptyList());

        searchService.search(new SearchRequest("system architecture", 10), "admin@example.com", true);

        verify(mockVectorStoreService).search(anyList(), eq(10), isNull());
    }

    // =========================================================================
    // 4. Batch Retrieval & Ranking Preservation
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL chunks are batch-retrieved and Qdrant relevance ranking is strictly preserved")
    void qdrantRankingPreservedAndBatchRetrieved() throws Exception {
        ScoredChunkDto match1 = new ScoredChunkDto(501L, 100L, 1, 0, 10L, 0.95f);
        ScoredChunkDto match2 = new ScoredChunkDto(502L, 100L, 2, 1, 10L, 0.88f);
        ScoredChunkDto match3 = new ScoredChunkDto(503L, 100L, 3, 2, 10L, 0.76f);

        when(mockVectorStoreService.search(eq(mockQueryVector), eq(3), eq(10L)))
                .thenReturn(List.of(match1, match2, match3));

        DocumentChunk chunk1 = createChunk(501L, testDoc, 1, 0, "Highest matching text");
        DocumentChunk chunk2 = createChunk(502L, testDoc, 2, 1, "Medium matching text");
        DocumentChunk chunk3 = createChunk(503L, testDoc, 3, 2, "Lowest matching text");

        // Simulate JPA repository returning chunks in arbitrary order (e.g. 503, 501, 502)
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(501L, 502L, 503L)))
                .thenReturn(List.of(chunk3, chunk1, chunk2));

        SearchResponse response = searchService.search(new SearchRequest("leave policy", 3), "user@example.com", false);

        assertThat(response.results()).hasSize(3);
        // Verify order strictly matches Qdrant score ranking: 0.95 -> 0.88 -> 0.76
        assertThat(response.results().get(0).chunkId()).isEqualTo(501L);
        assertThat(response.results().get(0).score()).isEqualTo(0.95f);
        assertThat(response.results().get(0).text()).isEqualTo("Highest matching text");

        assertThat(response.results().get(1).chunkId()).isEqualTo(502L);
        assertThat(response.results().get(1).score()).isEqualTo(0.88f);
        assertThat(response.results().get(1).text()).isEqualTo("Medium matching text");

        assertThat(response.results().get(2).chunkId()).isEqualTo(503L);
        assertThat(response.results().get(2).score()).isEqualTo(0.76f);
        assertThat(response.results().get(2).text()).isEqualTo("Lowest matching text");

        // Verify single batch query was made (no N+1 queries)
        verify(mockChunkRepository, times(1)).findAllWithDocumentAndOwnerByIdIn(any());
    }

    // =========================================================================
    // 5. Stale Qdrant Point Handling
    // =========================================================================

    @Test
    @DisplayName("Stale Qdrant points missing from PostgreSQL are safely skipped without failing the search")
    void staleQdrantPointsSafelySkipped() throws Exception {
        ScoredChunkDto staleMatch = new ScoredChunkDto(9999L, 100L, 1, 0, 10L, 0.99f);
        ScoredChunkDto validMatch = new ScoredChunkDto(501L, 100L, 1, 0, 10L, 0.85f);

        when(mockVectorStoreService.search(eq(mockQueryVector), eq(5), eq(10L)))
                .thenReturn(List.of(staleMatch, validMatch));

        DocumentChunk chunk1 = createChunk(501L, testDoc, 1, 0, "Valid chunk text");
        // DB only returns validMatch (staleMatch 9999 is missing from DB)
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(9999L, 501L)))
                .thenReturn(List.of(chunk1));

        SearchResponse response = searchService.search(new SearchRequest("leave policy", 5), "user@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(501L);
        assertThat(response.results().get(0).text()).isEqualTo("Valid chunk text");
    }

    // =========================================================================
    // 6. Defense-in-Depth Ownership Verification
    // =========================================================================

    @Test
    @DisplayName("Defense-in-depth: cross-tenant chunk returned from vector store is filtered out for USER")
    void crossTenantChunkFilteredOutForUser() throws Exception {
        ScoredChunkDto foreignMatch = new ScoredChunkDto(777L, 200L, 1, 0, 99L, 0.98f);
        ScoredChunkDto ownMatch = new ScoredChunkDto(501L, 100L, 1, 0, 10L, 0.85f);

        when(mockVectorStoreService.search(eq(mockQueryVector), eq(5), eq(10L)))
                .thenReturn(List.of(foreignMatch, ownMatch));

        DocumentChunk foreignChunk = createChunk(777L, otherDoc, 1, 0, "Confidential data of user B");
        DocumentChunk ownChunk = createChunk(501L, testDoc, 1, 0, "My document data");

        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(777L, 501L)))
                .thenReturn(List.of(foreignChunk, ownChunk));

        SearchResponse response = searchService.search(new SearchRequest("query", 5), "user@example.com", false);

        // foreignChunk is owned by otherUser (99L) -> must be dropped
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(501L);
        assertThat(response.results().get(0).text()).isEqualTo("My document data");
    }

    // =========================================================================
    // 7. Error Handling & Failure Propagation
    // =========================================================================

    @Test
    @DisplayName("Embedding service failure propagates as RuntimeException")
    void embeddingFailurePropagates() {
        when(mockEmbeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("Gemini API connection refused"));

        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", 5), "user@example.com", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Gemini API connection refused");

        verify(mockVectorStoreService, never()).search(any(), anyInt(), any());
    }

    @Test
    @DisplayName("Vector store search failure propagates as RuntimeException")
    void vectorStoreFailurePropagates() {
        when(mockVectorStoreService.search(anyList(), anyInt(), any()))
                .thenThrow(new RuntimeException("Qdrant gRPC unavailable"));

        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", 5), "user@example.com", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Qdrant gRPC unavailable");
    }

    @Test
    @DisplayName("Unknown user email throws ResourceNotFoundException")
    void unknownUserEmailThrowsException() {
        when(mockUserRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> searchService.search(new SearchRequest("query", 5), "unknown@example.com", false))
                .isInstanceOf(com.enterprise.aiknowledge.exception.ResourceNotFoundException.class)
                .hasMessageContaining("User not found with email: unknown@example.com");
    }
}
