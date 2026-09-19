package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.RagRequest;
import com.enterprise.aiknowledge.exception.GenerationServiceException;
import com.enterprise.aiknowledge.model.*;
import com.enterprise.aiknowledge.repository.*;
import com.enterprise.aiknowledge.service.EmbeddingService;
import com.enterprise.aiknowledge.service.GenerationService;
import com.enterprise.aiknowledge.service.PasswordHashingService;
import com.enterprise.aiknowledge.service.ScoredChunkDto;
import com.enterprise.aiknowledge.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc integration and security tests for {@code POST /api/rag/ask}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class RagControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentTextRepository documentTextRepository;
    @Autowired private DocumentChunkRepository documentChunkRepository;
    @Autowired private DocumentChunkEmbeddingRepository documentChunkEmbeddingRepository;
    @Autowired private PasswordHashingService passwordHashingService;

    @MockBean private EmbeddingService embeddingService;
    @MockBean private VectorStoreService vectorStoreService;
    @MockBean private GenerationService generationService;

    private static final String RAG_URL = "/api/rag/ask";
    private static final String USER_A_EMAIL = "rag_user_a@example.com";
    private static final String USER_B_EMAIL = "rag_user_b@example.com";
    private static final String ADMIN_EMAIL = "rag_admin@example.com";
    private static final String PASSWORD = "TestPassword123";

    private User userA;
    private User userB;
    private User admin;

    private final List<Float> mockVector = Collections.nCopies(768, 0.05f);

    @BeforeEach
    void setUp() {
        documentChunkEmbeddingRepository.deleteAll();
        documentChunkRepository.deleteAll();
        documentTextRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        when(embeddingService.generateEmbedding(any())).thenReturn(mockVector);

        userA = new User();
        userA.setName("User A");
        userA.setEmail(USER_A_EMAIL);
        userA.setPasswordHash(passwordHashingService.hash(PASSWORD));
        userA.setRole(Role.USER);
        userA = userRepository.save(userA);

        userB = new User();
        userB.setName("User B");
        userB.setEmail(USER_B_EMAIL);
        userB.setPasswordHash(passwordHashingService.hash(PASSWORD));
        userB.setRole(Role.USER);
        userB = userRepository.save(userB);

        admin = new User();
        admin.setName("Admin");
        admin.setEmail(ADMIN_EMAIL);
        admin.setPasswordHash(passwordHashingService.hash(PASSWORD));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
    }

    private Document createDocument(User owner, String filename) {
        Document doc = new Document();
        doc.setOwner(owner);
        doc.setOriginalFilename(filename);
        doc.setStoredFilename("stored_" + filename);
        doc.setContentType("application/pdf");
        doc.setFileSize(1024L);
        doc.setStoragePath("uploads-test/" + filename);
        doc.setStatus(DocumentStatus.COMPLETED);
        return documentRepository.save(doc);
    }

    private DocumentChunk createChunk(Document document, int pageNumber, int chunkIndex, String text) {
        DocumentChunk chunk = new DocumentChunk(document, chunkIndex, pageNumber, text, 0, text.length());
        return documentChunkRepository.save(chunk);
    }

    // =========================================================================
    // 1. Authentication & Validation Tests
    // =========================================================================

    @Test
    @DisplayName("Unauthenticated RAG request returns 401 Unauthorized")
    void unauthenticatedRequest_returns401() throws Exception {
        RagRequest request = new RagRequest("What is the leave policy?", 5);

        mockMvc.perform(post(RAG_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Blank query returns 400 Bad Request")
    void blankQuery_returns400() throws Exception {
        RagRequest request = new RagRequest("   ", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Invalid topK returns 400 Bad Request")
    void invalidTopK_returns400() throws Exception {
        RagRequest zeroTopK = new RagRequest("What is the policy?", 0);
        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroTopK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("topK must be between 1 and 20")));

        RagRequest excessiveTopK = new RagRequest("What is the policy?", 25);
        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessiveTopK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("topK must be between 1 and 20")));
    }

    // =========================================================================
    // 2. Successful RAG Generation & Grounding
    // =========================================================================

    @Test
    @DisplayName("Authenticated USER receives grounded answer and structured sources")
    void validRagRequest_success() throws Exception {
        Document docA = createDocument(userA, "leave_policy.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Employees receive 20 days of annual leave per year.");

        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(new ScoredChunkDto(
                        chunkA.getId(), docA.getId(), chunkA.getPageNumber(), chunkA.getChunkIndex(), userA.getId(), 0.94f)));

        when(generationService.generateAnswer(anyString(), anyString(), anyString()))
                .thenReturn("Employees are granted 20 days of annual leave each year.");

        RagRequest request = new RagRequest("What is the company's annual leave policy?", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query", is("What is the company's annual leave policy?")))
                .andExpect(jsonPath("$.answer", is("Employees are granted 20 days of annual leave each year.")))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].documentId", is(docA.getId().intValue())))
                .andExpect(jsonPath("$.sources[0].chunkId", is(chunkA.getId().intValue())))
                .andExpect(jsonPath("$.sources[0].pageNumber", is(1)))
                .andExpect(jsonPath("$.sources[0].chunkIndex", is(0)))
                .andExpect(jsonPath("$.sources[0].score", closeTo(0.94, 0.001)))
                // Security verification: verify sensitive fields are NOT present
                .andExpect(jsonPath("$.sources[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.sources[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.sources[0].ownerId").doesNotExist());
    }

    @Test
    @DisplayName("Insufficient context: returns conservative message without calling Gemini")
    void insufficientContext_returnsConservativeAnswerWithoutCallingGemini() throws Exception {
        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(Collections.emptyList());

        RagRequest request = new RagRequest("What is the Tokyo office address?", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query", is("What is the Tokyo office address?")))
                .andExpect(jsonPath("$.answer", containsString("not available in the provided documents")))
                .andExpect(jsonPath("$.sources", hasSize(0)));

        verifyNoInteractions(generationService);
    }

    @Test
    @DisplayName("ADMIN user can query across all documents")
    void adminUser_queriesAcrossAllDocuments() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Doc A content.");

        Document docB = createDocument(userB, "docB.pdf");
        DocumentChunk chunkB = createChunk(docB, 2, 0, "Doc B content.");

        // ADMIN passes null targetOwnerId to search cross-tenant
        when(vectorStoreService.search(anyList(), anyInt(), isNull()))
                .thenReturn(List.of(
                        new ScoredChunkDto(chunkA.getId(), docA.getId(), 1, 0, userA.getId(), 0.95f),
                        new ScoredChunkDto(chunkB.getId(), docB.getId(), 2, 0, userB.getId(), 0.91f)
                ));

        when(generationService.generateAnswer(anyString(), anyString(), anyString()))
                .thenReturn("Admin combined answer from both documents.");

        RagRequest request = new RagRequest("Summarize cross-team documents", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasSize(2)));
    }

    @Test
    @DisplayName("Gemini generation failure returns 502 Bad Gateway without leaking internal details")
    void generationFailure_returns502() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Content.");

        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(new ScoredChunkDto(
                        chunkA.getId(), docA.getId(), 1, 0, userA.getId(), 0.95f)));

        when(generationService.generateAnswer(anyString(), anyString(), anyString()))
                .thenThrow(new GenerationServiceException("Service unavailable"));

        RagRequest request = new RagRequest("Question?", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status", is(502)))
                .andExpect(jsonPath("$.message", containsString("AI generation service temporarily unavailable")));
    }

    // =========================================================================
    // 3. Multi-Tenant Security Tests
    // =========================================================================

    @Test
    @DisplayName("CRITICAL SECURITY TEST: User A never receives User B document chunks in context")
    void userANeverReceivesUserBChunksInContext() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "User A public memo.");

        Document docB = createDocument(userB, "docB.pdf");
        DocumentChunk chunkB = createChunk(docB, 1, 0, "User B top secret salary numbers.");

        // Even if Qdrant maliciously returned chunkB alongside chunkA:
        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(
                        new ScoredChunkDto(chunkA.getId(), docA.getId(), 1, 0, userA.getId(), 0.95f),
                        new ScoredChunkDto(chunkB.getId(), docB.getId(), 1, 0, userB.getId(), 0.94f)
                ));

        when(generationService.generateAnswer(anyString(), anyString(), anyString()))
                .thenReturn("Answer strictly for User A.");

        RagRequest request = new RagRequest("Show me information", 5);

        mockMvc.perform(post(RAG_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].documentId", is(docA.getId().intValue())));

        // Verify the actual prompt context sent to GenerationService
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationService).generateAnswer(anyString(), contextCaptor.capture(), anyString());

        String generatedContext = contextCaptor.getValue();
        assertTrue(generatedContext.contains("User A public memo"));
        assertFalse(generatedContext.contains("User B top secret salary numbers"));
    }
}
