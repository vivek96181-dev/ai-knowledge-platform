package com.enterprise.aiknowledge.kafka;

import com.enterprise.aiknowledge.dto.DocumentResponse;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.model.Role;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.FileStorageService;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying asynchronous Kafka document processing using an Embedded Kafka Broker.
 *
 * <p><strong>Scenarios Tested:</strong>
 * <ol>
 *   <li>Document upload creates metadata, writes physical file, returns status UPLOADED immediately.</li>
 *   <li>DocumentUploadedEvent is published and consumed asynchronously.</li>
 *   <li>Consumer transitions status UPLOADED → PROCESSING → COMPLETED.</li>
 *   <li>Missing document ID in consumer is handled safely without exception throwing.</li>
 *   <li>Missing physical file results in FAILED status.</li>
 *   <li>Empty/unreadable physical file results in FAILED status.</li>
 *   <li>Duplicate event for an already COMPLETED document is skipped (idempotent).</li>
 * </ol>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class DocumentKafkaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordHashingService passwordHashingService;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private DocumentEventProducer documentEventProducer;
    @Autowired private DocumentProcessingConsumer documentProcessingConsumer;
    @Autowired private ObjectMapper objectMapper;

    private static final String BASE_URL = "/api/documents";
    private static final String USER_EMAIL = "kafka_test_user@example.com";
    private static final String PASSWORD = "TestPassword123";

    private User testUser;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setName("Kafka User");
        testUser.setEmail(USER_EMAIL);
        testUser.setPasswordHash(passwordHashingService.hash(PASSWORD));
        testUser.setRole(Role.USER);
        testUser = userRepository.save(testUser);
    }

    // =========================================================================
    // TEST 1 & 2 & 3 & 4 & 5: Upload returns UPLOADED immediately → Consumer sets COMPLETED asynchronously
    // =========================================================================

    @Test
    @DisplayName("Upload returns UPLOADED immediately; Kafka consumer asynchronously transitions UPLOADED -> PROCESSING -> COMPLETED")
    void uploadReturnsUploadedImmediatelyAndConsumerCompletesAsynchronously() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 Asynchronous Kafka Processing Content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "async_report.pdf", "application/pdf", pdfBytes);

        // Step 1: Upload file via HTTP
        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();

        DocumentResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), DocumentResponse.class);

        // Step 2: Verify immediate state in DB & storage
        Document docInDb = documentRepository.findById(response.id()).orElseThrow();
        assertThat(docInDb.getOriginalFilename()).isEqualTo("async_report.pdf");
        assertThat(fileStorageService.fileExists(docInDb.getStoragePath())).isTrue();

        // Step 3: Await asynchronous consumer execution (UPLOADED -> PROCESSING -> COMPLETED)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Document updatedDoc = documentRepository.findById(response.id()).orElseThrow();
            assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        });
    }

    // =========================================================================
    // TEST 6: Missing document handled safely
    // =========================================================================

    @Test
    @DisplayName("Consumer handles non-existent document ID safely without failing")
    void consumerHandlesMissingDocumentSafely() {
        DocumentUploadedEvent event = new DocumentUploadedEvent(
                999999L,
                testUser.getId(),
                "/invalid/path/missing.pdf",
                "missing.pdf"
        );

        // Direct consumer execution — must complete without throwing exceptions
        documentProcessingConsumer.consume(event);

        assertThat(documentRepository.findById(999999L)).isEmpty();
    }

    // =========================================================================
    // TEST 7: Missing physical file results in FAILED
    // =========================================================================

    @Test
    @DisplayName("Consumer sets document status to FAILED when physical file does not exist on disk")
    void missingPhysicalFileResultsInFailedStatus() {
        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename("missing_disk_file.pdf");
        doc.setStoredFilename("stored_missing_disk_file.pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(100L);
        doc.setStoragePath("/non/existent/path/missing_disk_file.pdf");
        doc.setStatus(DocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                doc.getId(),
                testUser.getId(),
                doc.getStoragePath(),
                doc.getOriginalFilename()
        );

        documentProcessingConsumer.consume(event);

        Document updatedDoc = documentRepository.findById(doc.getId()).orElseThrow();
        assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // =========================================================================
    // TEST 8: Unreadable / Empty file results in FAILED
    // =========================================================================

    @Test
    @DisplayName("Consumer sets document status to FAILED when physical file is empty (0 bytes)")
    void emptyFileResultsInFailedStatus() throws Exception {
        // Create a 0-byte file in upload directory
        String emptyFilename = "empty_test_file.pdf";
        Path uploadDir = Paths.get("uploads-test").toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Path emptyFilePath = uploadDir.resolve(emptyFilename);
        Files.write(emptyFilePath, new byte[0]);

        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename(emptyFilename);
        doc.setStoredFilename(emptyFilename);
        doc.setContentType("application/pdf");
        doc.setFileSize(0L);
        doc.setStoragePath(emptyFilePath.toString());
        doc.setStatus(DocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                doc.getId(),
                testUser.getId(),
                doc.getStoragePath(),
                doc.getOriginalFilename()
        );

        documentProcessingConsumer.consume(event);

        Document updatedDoc = documentRepository.findById(doc.getId()).orElseThrow();
        assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.FAILED);

        // Clean up temp file
        Files.deleteIfExists(emptyFilePath);
    }

    // =========================================================================
    // TEST 9: Duplicate event does not process a COMPLETED document again
    // =========================================================================

    @Test
    @DisplayName("Consumer skips processing when document is already COMPLETED (idempotent)")
    void duplicateEventSkipsAlreadyCompletedDocument() {
        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename("already_done.pdf");
        doc.setStoredFilename("already_done.pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(200L);
        doc.setStoragePath("/path/to/already_done.pdf");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc = documentRepository.save(doc);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                doc.getId(),
                testUser.getId(),
                doc.getStoragePath(),
                doc.getOriginalFilename()
        );

        documentProcessingConsumer.consume(event);

        // Status remains COMPLETED
        Document updatedDoc = documentRepository.findById(doc.getId()).orElseThrow();
        assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }
}
