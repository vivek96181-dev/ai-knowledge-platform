package com.enterprise.aiknowledge.document;

import com.enterprise.aiknowledge.dto.DocumentResponse;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.LocalFileStorageService;
import com.enterprise.aiknowledge.service.PasswordHashingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive integration tests for Document Management.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class DocumentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private com.enterprise.aiknowledge.repository.DocumentChunkEmbeddingRepository documentChunkEmbeddingRepository;
    @Autowired private com.enterprise.aiknowledge.repository.DocumentChunkRepository documentChunkRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentTextRepository documentTextRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordHashingService passwordHashingService;
    @Autowired private LocalFileStorageService fileStorageService;
    @Autowired private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.enterprise.aiknowledge.service.VectorStoreService vectorStoreService;

    private static final String BASE_URL = "/api/documents";

    private static final String USER1_EMAIL = "user1@example.com";
    private static final String USER2_EMAIL = "user2@example.com";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String PASSWORD = "TestPassword123";

    private User user1;
    private User user2;
    private User admin;

    @BeforeEach
    void setUp() {
        documentChunkEmbeddingRepository.deleteAll();
        documentChunkRepository.deleteAll();
        documentTextRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Create User 1
        user1 = new User();
        user1.setName("User One");
        user1.setEmail(USER1_EMAIL);
        user1.setPasswordHash(passwordHashingService.hash(PASSWORD));
        user1.setRole(Role.USER);
        user1 = userRepository.save(user1);

        // Create User 2
        user2 = new User();
        user2.setName("User Two");
        user2.setEmail(USER2_EMAIL);
        user2.setPasswordHash(passwordHashingService.hash(PASSWORD));
        user2.setRole(Role.USER);
        user2 = userRepository.save(user2);

        // Create Admin
        admin = new User();
        admin.setName("Admin User");
        admin.setEmail(ADMIN_EMAIL);
        admin.setPasswordHash(passwordHashingService.hash(PASSWORD));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
    }

    // =========================================================================
    // TEST 1: Authenticated user uploads PDF
    // =========================================================================

    @Test
    @DisplayName("1. POST /api/documents → 201 CREATED when authenticated user uploads a valid PDF")
    void shouldUploadPdfSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "architecture.pdf", "application/pdf", "%PDF-1.4 Dummy Content".getBytes()
        );

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.originalFilename").value("architecture.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileSize").value(file.getSize()))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.ownerId").value(user1.getId()))
                .andExpect(jsonPath("$.ownerEmail").value(USER1_EMAIL));
    }

    // =========================================================================
    // TEST 2: Unauthenticated upload gets 401
    // =========================================================================

    @Test
    @DisplayName("2. POST /api/documents → 401 UNAUTHORIZED when unauthenticated")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "architecture.pdf", "application/pdf", "%PDF-1.4 Content".getBytes()
        );

        mockMvc.perform(multipart(BASE_URL).file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    // =========================================================================
    // TEST 3: Invalid file type rejected (400)
    // =========================================================================

    @Test
    @DisplayName("3. POST /api/documents → 400 BAD REQUEST when file is not a PDF")
    void shouldRejectNonPdfFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.exe", "application/x-msdownload", "binary data".getBytes()
        );

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid file type. Only PDF documents are allowed"));
    }

    // =========================================================================
    // TEST 4: Missing file rejected (400)
    // =========================================================================

    @Test
    @DisplayName("4. POST /api/documents → 400 BAD REQUEST when file is empty")
    void shouldRejectEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]
        );

        mockMvc.perform(multipart(BASE_URL)
                        .file(emptyFile)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("File is required and must not be empty"));
    }

    // =========================================================================
    // TEST 5: User can list own documents
    // =========================================================================

    @Test
    @DisplayName("5. GET /api/documents → 200 OK returning list of user's own documents only")
    void userCanListOwnDocuments() throws Exception {
        uploadSampleDocument(user1, "doc1.pdf");
        uploadSampleDocument(user1, "doc2.pdf");
        uploadSampleDocument(user2, "user2_doc.pdf");

        mockMvc.perform(get(BASE_URL)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].ownerEmail").value(USER1_EMAIL))
                .andExpect(jsonPath("$[1].ownerEmail").value(USER1_EMAIL));
    }

    // =========================================================================
    // TEST 6: User cannot access another user's document (403)
    // =========================================================================

    @Test
    @DisplayName("6. GET /api/documents/{id} → 403 FORBIDDEN when user attempts to view another user's document")
    void userCannotAccessAnotherUsersDocument() throws Exception {
        DocumentResponse doc2 = uploadSampleDocument(user2, "user2_private.pdf");

        mockMvc.perform(get(BASE_URL + "/" + doc2.id())
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied: You do not own this document"));
    }

    // =========================================================================
    // TEST 7: User cannot delete another user's document (403)
    // =========================================================================

    @Test
    @DisplayName("7. DELETE /api/documents/{id} → 403 FORBIDDEN when user attempts to delete another user's document")
    void userCannotDeleteAnotherUsersDocument() throws Exception {
        DocumentResponse doc2 = uploadSampleDocument(user2, "user2_important.pdf");

        mockMvc.perform(delete(BASE_URL + "/" + doc2.id())
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied: You do not own this document"));

        // Verify document is still in database
        assertThat(documentRepository.findById(doc2.id())).isPresent();
    }

    // =========================================================================
    // TEST 8: User can retrieve own document
    // =========================================================================

    @Test
    @DisplayName("8. GET /api/documents/{id} → 200 OK with metadata when user requests own document")
    void userCanRetrieveOwnDocument() throws Exception {
        DocumentResponse doc1 = uploadSampleDocument(user1, "my_report.pdf");

        mockMvc.perform(get(BASE_URL + "/" + doc1.id())
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc1.id()))
                .andExpect(jsonPath("$.originalFilename").value("my_report.pdf"))
                .andExpect(jsonPath("$.ownerEmail").value(USER1_EMAIL));
    }

    // =========================================================================
    // TEST 9: User can delete own document
    // =========================================================================

    @Test
    @DisplayName("9. DELETE /api/documents/{id} → 200 OK and removes document when owner deletes it")
    void userCanDeleteOwnDocument() throws Exception {
        DocumentResponse doc1 = uploadSampleDocument(user1, "temp.pdf");

        mockMvc.perform(delete(BASE_URL + "/" + doc1.id())
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isOk());

        // Verify document is removed from database
        assertThat(documentRepository.findById(doc1.id())).isEmpty();
    }

    // =========================================================================
    // TEST 10: Admin behavior follows configured authorization rules
    // =========================================================================

    @Test
    @DisplayName("10. ADMIN can list all documents and view/delete any user's document")
    void adminCanAccessAndManageAllDocuments() throws Exception {
        DocumentResponse doc1 = uploadSampleDocument(user1, "user1_doc.pdf");
        DocumentResponse doc2 = uploadSampleDocument(user2, "user2_doc.pdf");

        // Admin lists all documents across users
        mockMvc.perform(get(BASE_URL)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Admin retrieves user1's document
        mockMvc.perform(get(BASE_URL + "/" + doc1.id())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc1.id()));

        // Admin deletes user2's document
        mockMvc.perform(delete(BASE_URL + "/" + doc2.id())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());

        assertThat(documentRepository.findById(doc2.id())).isEmpty();
    }

    // =========================================================================
    // TEST 11: Metadata is stored correctly
    // =========================================================================

    @Test
    @DisplayName("11. Uploading a document stores correct metadata in PostgreSQL")
    void metadataIsStoredCorrectly() throws Exception {
        byte[] content = "%PDF-1.4 Valid PDF Content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "financials.pdf", "application/pdf", content);

        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), DocumentResponse.class);

        var savedDoc = documentRepository.findById(response.id()).orElseThrow();
        assertThat(savedDoc.getOriginalFilename()).isEqualTo("financials.pdf");
        assertThat(savedDoc.getContentType()).isEqualTo("application/pdf");
        assertThat(savedDoc.getFileSize()).isEqualTo(content.length);
        assertThat(savedDoc.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(savedDoc.getOwner().getId()).isEqualTo(user1.getId());
        assertThat(savedDoc.getStoragePath()).isNotEmpty();
    }

    // =========================================================================
    // TEST 12: File is actually written to local storage
    // =========================================================================

    @Test
    @DisplayName("12. Uploading a document physically writes the file to the local storage directory")
    void fileIsWrittenToLocalStorage() throws Exception {
        byte[] content = "%PDF-1.4 Physical Storage Test Content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "physical.pdf", "application/pdf", content);

        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), DocumentResponse.class);

        var savedDoc = documentRepository.findById(response.id()).orElseThrow();
        Path physicalPath = Paths.get(savedDoc.getStoragePath());

        assertThat(Files.exists(physicalPath)).isTrue();
        assertThat(Files.readAllBytes(physicalPath)).isEqualTo(content);
    }

    // =========================================================================
    // TEST 13: Deleting document removes physical file from local storage
    // =========================================================================

    @Test
    @DisplayName("13. Deleting a document removes both DB record and physical file from disk")
    void deletingDocumentRemovesStoredFile() throws Exception {
        byte[] content = "%PDF-1.4 Deletion Test Content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "to_delete.pdf", "application/pdf", content);

        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), DocumentResponse.class);

        var savedDoc = documentRepository.findById(response.id()).orElseThrow();
        Path physicalPath = Paths.get(savedDoc.getStoragePath());
        assertThat(Files.exists(physicalPath)).isTrue();

        // Delete document
        mockMvc.perform(delete(BASE_URL + "/" + response.id())
                        .with(user(USER1_EMAIL).roles("USER")))
                .andExpect(status().isOk());

        // Verify file is deleted from local disk
        assertThat(Files.exists(physicalPath)).isFalse();
    }

    // Helper method to upload a document for testing
    private DocumentResponse uploadSampleDocument(User ownerUser, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "application/pdf", ("%PDF-1.4 Sample Content for " + filename).getBytes()
        );

        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(ownerUser.getEmail()).roles(ownerUser.getRole().name())))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), DocumentResponse.class);
    }
}
