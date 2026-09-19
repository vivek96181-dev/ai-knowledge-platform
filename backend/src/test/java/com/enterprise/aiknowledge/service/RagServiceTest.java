package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.exception.GenerationServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagService Unit and Security Tests")
class RagServiceTest {

    @Mock
    private SearchService searchService;

    @Mock
    private GenerationService generationService;

    private ContextBuilder contextBuilder;
    private RagService ragService;

    private static final String USER_EMAIL = "user@example.com";
    private static final String QUERY = "What is the company leave policy?";

    @BeforeEach
    void setUp() {
        contextBuilder = new ContextBuilder(8000);
        ragService = new RagService(searchService, contextBuilder, generationService);

        lenient().when(searchService.getDefaultTopK()).thenReturn(5);
        lenient().when(searchService.getMaxTopK()).thenReturn(20);
    }

    @Test
    @DisplayName("ask succeeds with valid query and maps sources preserving retrieval order")
    void ask_validRequest_success() {
        RagRequest request = new RagRequest(QUERY, 5);

        List<SearchResult> searchResults = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "Employees get 20 days annual leave."),
                new SearchResult(10L, 102L, 2, 1, 0.88f, "Up to 5 unused days can be carried forward.")
        );
        when(searchService.search(any(), eq(USER_EMAIL), eq(false)))
                .thenReturn(new SearchResponse(QUERY, searchResults));

        when(generationService.generateAnswer(anyString(), anyString(), eq(QUERY)))
                .thenReturn("Employees receive 20 days of annual leave and can carry forward up to 5 days.");

        RagResponse response = ragService.ask(request, USER_EMAIL, false);

        assertNotNull(response);
        assertEquals(QUERY, response.query());
        assertEquals("Employees receive 20 days of annual leave and can carry forward up to 5 days.", response.answer());
        assertEquals(2, response.sources().size());

        // Verify source 1
        RagSource s1 = response.sources().get(0);
        assertEquals(10L, s1.documentId());
        assertEquals(101L, s1.chunkId());
        assertEquals(1, s1.pageNumber());
        assertEquals(0, s1.chunkIndex());
        assertEquals(0.95f, s1.score());

        // Verify source 2
        RagSource s2 = response.sources().get(1);
        assertEquals(10L, s2.documentId());
        assertEquals(102L, s2.chunkId());
        assertEquals(2, s2.pageNumber());
        assertEquals(1, s2.chunkIndex());
        assertEquals(0.88f, s2.score());

        // Verify generation call arguments
        ArgumentCaptor<String> instructionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);

        verify(generationService).generateAnswer(
                instructionCaptor.capture(),
                contextCaptor.capture(),
                questionCaptor.capture()
        );

        assertTrue(instructionCaptor.getValue().contains("enterprise knowledge assistant"));
        assertTrue(contextCaptor.getValue().contains("[SOURCE 1]"));
        assertTrue(contextCaptor.getValue().contains("Employees get 20 days annual leave."));
        assertEquals(QUERY, questionCaptor.getValue());
    }

    @Test
    @DisplayName("ask rejects blank query or query exceeding 1000 chars")
    void ask_rejectsInvalidQueries() {
        assertThrows(IllegalArgumentException.class, () ->
                ragService.ask(new RagRequest(null, 5), USER_EMAIL, false));
        assertThrows(IllegalArgumentException.class, () ->
                ragService.ask(new RagRequest("   ", 5), USER_EMAIL, false));

        String longQuery = "a".repeat(1001);
        assertThrows(IllegalArgumentException.class, () ->
                ragService.ask(new RagRequest(longQuery, 5), USER_EMAIL, false));
    }

    @Test
    @DisplayName("ask validates topK bounds")
    void ask_validatesTopK() {
        assertThrows(IllegalArgumentException.class, () ->
                ragService.ask(new RagRequest(QUERY, 0), USER_EMAIL, false));
        assertThrows(IllegalArgumentException.class, () ->
                ragService.ask(new RagRequest(QUERY, 21), USER_EMAIL, false));
    }

    @Test
    @DisplayName("ask reuses defaultTopK when topK is omitted")
    void ask_usesDefaultTopKWhenOmitted() {
        RagRequest request = new RagRequest(QUERY, null);

        when(searchService.search(any(), eq(USER_EMAIL), eq(false)))
                .thenReturn(new SearchResponse(QUERY, Collections.emptyList()));

        ragService.ask(request, USER_EMAIL, false);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture(), eq(USER_EMAIL), eq(false));
        assertEquals(5, captor.getValue().topK());
    }

    @Test
    @DisplayName("ask does not call Gemini when search returns 0 results (insufficient context)")
    void ask_insufficientContext_returnsConservativeMessageWithoutCallingGemini() {
        RagRequest request = new RagRequest(QUERY, 5);

        when(searchService.search(any(), eq(USER_EMAIL), eq(false)))
                .thenReturn(new SearchResponse(QUERY, Collections.emptyList()));

        RagResponse response = ragService.ask(request, USER_EMAIL, false);

        assertNotNull(response);
        assertEquals(QUERY, response.query());
        assertEquals(RagService.DEFAULT_INSUFFICIENT_CONTEXT_MESSAGE, response.answer());
        assertTrue(response.sources().isEmpty());

        // CRITICAL: Ensure GenerationService is never invoked
        verifyNoInteractions(generationService);
    }

    @Test
    @DisplayName("ask limits sources to only chunks that fit within context budget")
    void ask_contextBudgetEnforced_sourcesReflectOnlyIncludedChunks() {
        ContextBuilder tinyContextBuilder = new ContextBuilder(120);
        RagService customRagService = new RagService(searchService, tinyContextBuilder, generationService);

        RagRequest request = new RagRequest(QUERY, 5);
        List<SearchResult> searchResults = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "Short chunk 1"),
                new SearchResult(10L, 102L, 2, 1, 0.88f, "Second chunk that will overflow the 120 char budget.")
        );

        when(searchService.search(any(), eq(USER_EMAIL), eq(false)))
                .thenReturn(new SearchResponse(QUERY, searchResults));
        when(generationService.generateAnswer(anyString(), anyString(), eq(QUERY)))
                .thenReturn("Answer based only on chunk 1.");

        RagResponse response = customRagService.ask(request, USER_EMAIL, false);

        // Only chunk 1 fit in budget, so only chunk 1 is in sources
        assertEquals(1, response.sources().size());
        assertEquals(101L, response.sources().get(0).chunkId());
    }

    @Test
    @DisplayName("ask propagates GenerationServiceException when generation fails")
    void ask_propagatesGenerationServiceException() {
        RagRequest request = new RagRequest(QUERY, 5);

        List<SearchResult> searchResults = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "Chunk text.")
        );
        when(searchService.search(any(), eq(USER_EMAIL), eq(false)))
                .thenReturn(new SearchResponse(QUERY, searchResults));

        when(generationService.generateAnswer(anyString(), anyString(), eq(QUERY)))
                .thenThrow(new GenerationServiceException("Rate limit exceeded"));

        assertThrows(GenerationServiceException.class, () ->
                ragService.ask(request, USER_EMAIL, false));
    }

    @Test
    @DisplayName("CRITICAL SECURITY TEST: User A query never receives chunks from User B")
    void ask_securityTest_userANeverReceivesUserBChunks() {
        String userAEmail = "userA@example.com";
        RagRequest request = new RagRequest("Confidential salary question", 5);

        // SemanticSearchService enforces multi-tenant security based on currentUserEmail and isAdmin.
        // For User A (isAdmin = false), SemanticSearchService only returns User A's authorized documents.
        List<SearchResult> userAAuthorizedResults = List.of(
                new SearchResult(100L, 501L, 1, 0, 0.92f, "User A confidential document content.")
        );

        when(searchService.search(any(), eq(userAEmail), eq(false)))
                .thenReturn(new SearchResponse(request.query(), userAAuthorizedResults));

        when(generationService.generateAnswer(anyString(), anyString(), anyString()))
                .thenReturn("Answer based on User A's document.");

        RagResponse response = ragService.ask(request, userAEmail, false);

        // Verify context sent to GenerationService
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationService).generateAnswer(anyString(), contextCaptor.capture(), anyString());

        String generatedContext = contextCaptor.getValue();
        assertTrue(generatedContext.contains("Document ID: 100"));
        assertTrue(generatedContext.contains("User A confidential document content."));
        assertFalse(generatedContext.contains("User B"));

        // Verify returned sources
        assertEquals(1, response.sources().size());
        assertEquals(100L, response.sources().get(0).documentId());
    }
}
