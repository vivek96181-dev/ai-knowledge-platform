package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.CandidateResult;
import com.enterprise.aiknowledge.dto.RetrievalSourceType;
import com.enterprise.aiknowledge.dto.SearchMode;
import com.enterprise.aiknowledge.dto.SearchRequest;
import com.enterprise.aiknowledge.dto.SearchResponse;
import com.enterprise.aiknowledge.dto.SearchResult;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentChunkEmbeddingRepository;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.EmbeddingService;
import com.enterprise.aiknowledge.service.KeywordSearchService;
import com.enterprise.aiknowledge.service.PasswordHashingService;
import com.enterprise.aiknowledge.service.ScoredChunkDto;
import com.enterprise.aiknowledge.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration and security tests for {@code POST /api/search} and {@code POST /api/search/hybrid}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class SearchControllerTest {

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
    @MockBean private KeywordSearchService keywordSearchService;

    private static final String SEARCH_URL = "/api/search";
    private static final String USER_A_EMAIL = "user_a@example.com";
    private static final String USER_B_EMAIL = "user_b@example.com";
    private static final String ADMIN_EMAIL = "admin_search@example.com";
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
    @DisplayName("Unauthenticated search request returns 401 Unauthorized")
    void unauthenticatedSearchReturns401() throws Exception {
        SearchRequest request = new SearchRequest("leave policy", 5);

        mockMvc.perform(post(SEARCH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Blank query returns 400 Bad Request")
    void blankQueryReturns400() throws Exception {
        SearchRequest request = new SearchRequest("   ", 5);

        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Invalid topK returns 400 Bad Request")
    void invalidTopKReturns400() throws Exception {
        SearchRequest zeroTopK = new SearchRequest("leave policy", 0);
        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroTopK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("topK must be between 1 and 20")));

        SearchRequest excessiveTopK = new SearchRequest("leave policy", 25);
        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessiveTopK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("topK must be between 1 and 20")));
    }

    // =========================================================================
    // 2. Successful Search & Multi-Tenant Security Tests
    // =========================================================================

    @Test
    @DisplayName("User A search retrieves only chunks from User A's document; User B's chunks never appear")
    void userSearchEnforcesMultiTenantIsolation() throws Exception {
        // Document A owned by User A
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Company leave policy allows 20 days paid leave per year.");

        // Document B owned by User B
        Document docB = createDocument(userB, "docB.pdf");
        DocumentChunk chunkB = createChunk(docB, 1, 0, "Confidential executive compensation and salary data.");

        // Mock vector store returning chunkA when filtered by User A's ID
        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(new ScoredChunkDto(
                        chunkA.getId(), docA.getId(), chunkA.getPageNumber(), chunkA.getChunkIndex(), userA.getId(), 0.93f)));

        SearchRequest request = new SearchRequest("leave policy", 5);

        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("leave policy"))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].documentId").value(docA.getId()))
                .andExpect(jsonPath("$.results[0].chunkId").value(chunkA.getId()))
                .andExpect(jsonPath("$.results[0].score").value(0.93))
                .andExpect(jsonPath("$.results[0].text").value("Company leave policy allows 20 days paid leave per year."))
                // Strict check: User B's chunk text must not appear anywhere
                .andExpect(jsonPath("$.results[*].text", not(hasItem(containsString("Confidential")))))
                // Verify no raw vector is returned
                .andExpect(jsonPath("$.results[0].vector").doesNotExist())
                .andExpect(jsonPath("$.results[0].embedding").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN search searches cross-tenant and can retrieve chunks from both User A and User B")
    void adminSearchCanRetrieveAcrossTenants() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "User A technical guidelines.");

        Document docB = createDocument(userB, "docB.pdf");
        DocumentChunk chunkB = createChunk(docB, 1, 0, "User B compliance standards.");

        // When searching as ADMIN (ownerId is null), vector store returns matches from both users
        when(vectorStoreService.search(anyList(), anyInt(), isNull()))
                .thenReturn(List.of(
                        new ScoredChunkDto(chunkA.getId(), docA.getId(), 1, 0, userA.getId(), 0.95f),
                        new ScoredChunkDto(chunkB.getId(), docB.getId(), 1, 0, userB.getId(), 0.89f)
                ));

        SearchRequest request = new SearchRequest("guidelines standards", 10);

        mockMvc.perform(post(SEARCH_URL)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].chunkId").value(chunkA.getId()))
                .andExpect(jsonPath("$.results[0].score").value(0.95))
                .andExpect(jsonPath("$.results[1].chunkId").value(chunkB.getId()))
                .andExpect(jsonPath("$.results[1].score").value(0.89));
    }

    // =========================================================================
    // 3. Hybrid Search & Keyword Search Tests
    // =========================================================================

    @Test
    @DisplayName("Search with mode HYBRID executes Reciprocal Rank Fusion of vector and keyword matches")
    void searchWithHybridModeFusesResults() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunk1 = createChunk(docA, 1, 0, "Chunk 1 leave policy.");
        DocumentChunk chunk2 = createChunk(docA, 2, 1, "Chunk 2 vacation time.");

        // Semantic returns chunk1 (rank 1), chunk2 (rank 2)
        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(
                        new ScoredChunkDto(chunk1.getId(), docA.getId(), 1, 0, userA.getId(), 0.90f),
                        new ScoredChunkDto(chunk2.getId(), docA.getId(), 2, 1, userA.getId(), 0.70f)
                ));

        // Keyword returns chunk2 (rank 1)
        when(keywordSearchService.retrieveCandidates(eq("leave policy"), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(
                        new CandidateResult(chunk2.getId(), docA.getId(), 0.85f, 1, RetrievalSourceType.KEYWORD)
                ));

        SearchRequest request = new SearchRequest("leave policy", 5, SearchMode.HYBRID);

        // chunk2 gets boosted by appearing in both lists: (1/62) + (1/61) > (1/61)
        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("leave policy"))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].chunkId").value(chunk2.getId()))
                .andExpect(jsonPath("$.results[0].text").value("Chunk 2 vacation time."))
                .andExpect(jsonPath("$.results[1].chunkId").value(chunk1.getId()))
                .andExpect(jsonPath("$.results[1].text").value("Chunk 1 leave policy."));
    }

    @Test
    @DisplayName("Search with mode KEYWORD routes to KeywordSearchService")
    void searchWithKeywordModeRoutesToKeywordService() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Keyword matched text.");

        SearchResponse mockResponse = new SearchResponse("keyword query", List.of(
                new SearchResult(docA.getId(), chunkA.getId(), 1, 0, 0.75f, "Keyword matched text.")
        ));
        when(keywordSearchService.search(any(), eq(USER_A_EMAIL), eq(false))).thenReturn(mockResponse);

        SearchRequest request = new SearchRequest("keyword query", 5, SearchMode.KEYWORD);

        mockMvc.perform(post(SEARCH_URL)
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("keyword query"))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].chunkId").value(chunkA.getId()));
    }

    @Test
    @DisplayName("Dedicated /api/search/hybrid endpoint returns RRF fused results")
    void dedicatedHybridEndpointWorks() throws Exception {
        Document docA = createDocument(userA, "docA.pdf");
        DocumentChunk chunkA = createChunk(docA, 1, 0, "Hybrid chunk content.");

        when(vectorStoreService.search(anyList(), anyInt(), eq(userA.getId())))
                .thenReturn(List.of(
                        new ScoredChunkDto(chunkA.getId(), docA.getId(), 1, 0, userA.getId(), 0.95f)
                ));
        when(keywordSearchService.retrieveCandidates(anyString(), anyInt(), eq(userA.getId())))
                .thenReturn(Collections.emptyList());

        SearchRequest request = new SearchRequest("hybrid test", 5);

        mockMvc.perform(post("/api/search/hybrid")
                        .with(user(USER_A_EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("hybrid test"))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].chunkId").value(chunkA.getId()));
    }

    @Test
    @DisplayName("Unauthenticated /api/search/hybrid request returns 401 Unauthorized")
    void unauthenticatedHybridEndpointReturns401() throws Exception {
        SearchRequest request = new SearchRequest("hybrid test", 5);

        mockMvc.perform(post("/api/search/hybrid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}

