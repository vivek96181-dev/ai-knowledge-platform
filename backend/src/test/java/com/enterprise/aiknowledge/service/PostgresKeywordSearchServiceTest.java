package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.ChunkKeywordMatch;
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
 * Unit tests for {@link PostgresKeywordSearchService} covering query dispatch,
 * candidate formatting, owner filtering, and standalone keyword search hydration.
 */
class PostgresKeywordSearchServiceTest {

    private DocumentChunkRepository mockChunkRepository;
    private UserRepository mockUserRepository;
    private PostgresKeywordSearchService keywordSearchService;

    private User testUser;
    private Document testDoc;

    @BeforeEach
    void setUp() throws Exception {
        mockChunkRepository = mock(DocumentChunkRepository.class);
        mockUserRepository = mock(UserRepository.class);

        keywordSearchService = new PostgresKeywordSearchService(
                mockChunkRepository,
                mockUserRepository,
                "english",
                5,
                20
        );

        testUser = new User();
        setEntityId(testUser, 10L);
        testUser.setEmail("user@example.com");
        testUser.setRole(Role.USER);

        testDoc = new Document();
        setEntityId(testDoc, 100L);
        testDoc.setOwner(testUser);

        when(mockUserRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
    }

    private void setEntityId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private ChunkKeywordMatch createMatch(Long chunkId, Long docId, Float score) {
        return new ChunkKeywordMatch() {
            @Override public Long getChunkId() { return chunkId; }
            @Override public Long getDocumentId() { return docId; }
            @Override public Float getScore() { return score; }
        };
    }

    private DocumentChunk createChunk(Long id, Document document, int pageNumber, int chunkIndex, String text) throws Exception {
        DocumentChunk chunk = new DocumentChunk(document, chunkIndex, pageNumber, text, 0, text.length());
        setEntityId(chunk, id);
        return chunk;
    }

    @Test
    @DisplayName("Candidate retrieval for USER invokes searchKeywordByOwner with 1-based ranks")
    void retrieveCandidatesForUser() {
        ChunkKeywordMatch match1 = createMatch(501L, 100L, 0.9f);
        ChunkKeywordMatch match2 = createMatch(502L, 100L, 0.7f);

        when(mockChunkRepository.searchKeywordByOwner(eq("leave policy"), eq("english"), eq(10L), eq(5)))
                .thenReturn(List.of(match1, match2));

        List<CandidateResult> candidates = keywordSearchService.retrieveCandidates("leave policy", 5, 10L);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).chunkId()).isEqualTo(501L);
        assertThat(candidates.get(0).retrievalRank()).isEqualTo(1);
        assertThat(candidates.get(0).sourceType()).isEqualTo(RetrievalSourceType.KEYWORD);
        assertThat(candidates.get(0).score()).isEqualTo(0.9f);

        assertThat(candidates.get(1).chunkId()).isEqualTo(502L);
        assertThat(candidates.get(1).retrievalRank()).isEqualTo(2);
        assertThat(candidates.get(1).sourceType()).isEqualTo(RetrievalSourceType.KEYWORD);
        assertThat(candidates.get(1).score()).isEqualTo(0.7f);

        verify(mockChunkRepository).searchKeywordByOwner("leave policy", "english", 10L, 5);
        verify(mockChunkRepository, never()).searchKeywordAll(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Candidate retrieval for ADMIN (null ownerId) invokes searchKeywordAll")
    void retrieveCandidatesForAdmin() {
        ChunkKeywordMatch match = createMatch(501L, 100L, 0.85f);
        when(mockChunkRepository.searchKeywordAll(eq("quarterly report"), eq("english"), eq(10)))
                .thenReturn(List.of(match));

        List<CandidateResult> candidates = keywordSearchService.retrieveCandidates("quarterly report", 10, null);

        assertThat(candidates).hasSize(1);
        verify(mockChunkRepository).searchKeywordAll("quarterly report", "english", 10);
        verify(mockChunkRepository, never()).searchKeywordByOwner(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Blank query returns empty candidate list without executing database queries")
    void blankQueryReturnsEmptyCandidates() {
        List<CandidateResult> candidates = keywordSearchService.retrieveCandidates("   ", 5, 10L);
        assertThat(candidates).isEmpty();
        verifyNoInteractions(mockChunkRepository);
    }

    @Test
    @DisplayName("Standalone keyword search hydrates chunks from PostgreSQL in ranked order")
    void standaloneKeywordSearchHydratesResults() throws Exception {
        ChunkKeywordMatch match1 = createMatch(501L, 100L, 0.95f);
        when(mockChunkRepository.searchKeywordByOwner(eq("leave"), eq("english"), eq(10L), eq(5)))
                .thenReturn(List.of(match1));

        DocumentChunk chunk1 = createChunk(501L, testDoc, 1, 0, "Company leave policy.");
        when(mockChunkRepository.findAllWithDocumentAndOwnerByIdIn(List.of(501L)))
                .thenReturn(List.of(chunk1));

        SearchResponse response = keywordSearchService.search(
                new SearchRequest("leave", 5, SearchMode.KEYWORD), "user@example.com", false);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(501L);
        assertThat(response.results().get(0).score()).isEqualTo(0.95f);
        assertThat(response.results().get(0).text()).isEqualTo("Company leave policy.");
    }

    @Test
    @DisplayName("Blank search request query throws IllegalArgumentException")
    void blankSearchQueryThrowsException() {
        assertThatThrownBy(() -> keywordSearchService.search(
                new SearchRequest("  ", 5, SearchMode.KEYWORD), "user@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query cannot be blank");
    }

    @Test
    @DisplayName("Unknown user throws ResourceNotFoundException")
    void unknownUserThrowsException() {
        when(mockUserRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> keywordSearchService.search(
                new SearchRequest("query", 5, SearchMode.KEYWORD), "unknown@example.com", false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with email: unknown@example.com");
    }
}
