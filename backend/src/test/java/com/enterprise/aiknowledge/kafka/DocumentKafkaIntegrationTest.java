package com.enterprise.aiknowledge.kafka;

import com.enterprise.aiknowledge.dto.DocumentResponse;
import com.enterprise.aiknowledge.model.*;
import com.enterprise.aiknowledge.repository.DocumentChunkEmbeddingRepository;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import com.enterprise.aiknowledge.service.ChunkVectorDto;
import com.enterprise.aiknowledge.service.EmbeddingService;
import com.enterprise.aiknowledge.service.FileStorageService;
import com.enterprise.aiknowledge.service.PasswordHashingService;
import com.enterprise.aiknowledge.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying asynchronous Kafka document processing, PDF text extraction, chunking,
 * embedding metadata persistence, and Qdrant vector indexing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"document-uploaded"})
class DocumentKafkaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentTextRepository documentTextRepository;
    @Autowired private DocumentChunkRepository documentChunkRepository;
    @Autowired private DocumentChunkEmbeddingRepository documentChunkEmbeddingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordHashingService passwordHashingService;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private DocumentEventProducer documentEventProducer;
    @Autowired private DocumentProcessingConsumer documentProcessingConsumer;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private EmbeddingService embeddingService;
    @MockBean private VectorStoreService vectorStoreService;

    private static final String BASE_URL = "/api/documents";
    private static final String USER_EMAIL = "kafka_test_user@example.com";
    private static final String PASSWORD = "TestPassword123";

    private User testUser;
    private final List<Float> mock768Vector = Collections.nCopies(768, 0.05f);

    @BeforeEach
    void setUp() {
        documentChunkEmbeddingRepository.deleteAll();
        documentChunkRepository.deleteAll();
        documentTextRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Default mock behavior for offline testing
        when(embeddingService.getModel()).thenReturn("gemini-embedding-2");
        when(embeddingService.getDimensions()).thenReturn(768);
        when(embeddingService.generateBatchEmbeddings(anyList())).thenAnswer(invocation -> {
            List<DocumentChunk> chunks = invocation.getArgument(0);
            Map<Long, List<Float>> map = new LinkedHashMap<>();
            for (DocumentChunk chunk : chunks) {
                map.put(chunk.getId(), mock768Vector);
            }
            return map;
        });

        when(vectorStoreService.getCollectionName()).thenReturn("document_chunks_test");
        when(vectorStoreService.getVectorDimensions()).thenReturn(768);

        testUser = new User();
        testUser.setName("Kafka User");
        testUser.setEmail(USER_EMAIL);
        testUser.setPasswordHash(passwordHashingService.hash(PASSWORD));
        testUser.setRole(Role.USER);
        testUser = userRepository.save(testUser);
    }

    // =========================================================================
    // TEST 1: PDF upload -> Kafka -> Text Extraction -> Chunks & Embeddings -> Qdrant -> COMPLETED
    // =========================================================================

    @Test
    @DisplayName("Upload valid PDF returns UPLOADED; Kafka consumer extracts text, chunks, embeds, indexes into Qdrant, and sets COMPLETED")
    void uploadValidPdfExtractsTextChunksAndEmbeddingsAsynchronously() throws Exception {
        byte[] pdfBytes = createSamplePdfBytes("Enterprise AI Knowledge Platform Extraction Test", 2);
        MockMultipartFile file = new MockMultipartFile("file", "extraction_test.pdf", "application/pdf", pdfBytes);

        // Step 1: Upload via HTTP API
        MvcResult result = mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();

        DocumentResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), DocumentResponse.class);

        // Step 2: Await consumer processing
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Document updatedDoc = documentRepository.findById(response.id()).orElseThrow();
            assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

            // Verify DocumentText
            Optional<DocumentText> docTextOpt = documentTextRepository.findByDocumentId(response.id());
            assertThat(docTextOpt).isPresent();
            DocumentText docText = docTextOpt.get();
            assertThat(docText.getPageCount()).isEqualTo(2);

            // Verify DocumentChunks
            List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(response.id());
            assertThat(chunks).hasSize(2);

            // Verify DocumentChunkEmbedding metadata in PostgreSQL
            List<DocumentChunkEmbedding> embeddings =
                    documentChunkEmbeddingRepository.findByDocumentChunkDocumentId(response.id());
            assertThat(embeddings).hasSize(2);

            // Verify Qdrant vectorStoreService was called with 2 vectors
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ChunkVectorDto>> vectorCaptor = ArgumentCaptor.forClass(List.class);
            verify(vectorStoreService).upsertChunkVectors(vectorCaptor.capture());

            List<ChunkVectorDto> upsertedVectors = vectorCaptor.getValue();
            assertThat(upsertedVectors).hasSize(2);
            assertThat(upsertedVectors.get(0).documentId()).isEqualTo(response.id());
            assertThat(upsertedVectors.get(0).vector()).hasSize(768);
            assertThat(upsertedVectors.get(0).ownerId()).isEqualTo(testUser.getId());
        });
    }

    // =========================================================================
    // TEST 2: Missing document ID handled safely
    // =========================================================================

    @Test
    @DisplayName("Consumer handles non-existent document ID safely without throwing exceptions")
    void consumerHandlesMissingDocumentSafely() {
        DocumentUploadedEvent event = new DocumentUploadedEvent(
                999999L,
                testUser.getId(),
                "/invalid/path/missing.pdf",
                "missing.pdf"
        );

        documentProcessingConsumer.consume(event);
        assertThat(documentRepository.findById(999999L)).isEmpty();
    }

    // =========================================================================
    // TEST 3: Missing physical file results in FAILED
    // =========================================================================

    @Test
    @DisplayName("Consumer sets status to FAILED when physical file is missing from disk")
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
        assertThat(documentTextRepository.findByDocumentId(doc.getId())).isEmpty();
        assertThat(documentChunkRepository.existsByDocumentId(doc.getId())).isFalse();
        assertThat(documentChunkEmbeddingRepository.findByDocumentChunkDocumentId(doc.getId())).isEmpty();
        verify(vectorStoreService, never()).upsertChunkVectors(anyList());
    }

    // =========================================================================
    // TEST 4: Corrupt PDF results in FAILED status
    // =========================================================================

    @Test
    @DisplayName("Consumer sets status to FAILED when physical PDF file is corrupt/invalid")
    void corruptPdfResultsInFailedStatus() throws Exception {
        String corruptFilename = "corrupt_test_file.pdf";
        Path uploadDir = Paths.get("uploads-test").toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Path corruptFilePath = uploadDir.resolve(corruptFilename);
        Files.write(corruptFilePath, "Not a valid PDF format content".getBytes());

        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename(corruptFilename);
        doc.setStoredFilename(corruptFilename);
        doc.setContentType("application/pdf");
        doc.setFileSize((long) "Not a valid PDF format content".getBytes().length);
        doc.setStoragePath(corruptFilePath.toString());
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
        assertThat(documentTextRepository.findByDocumentId(doc.getId())).isEmpty();
        assertThat(documentChunkRepository.existsByDocumentId(doc.getId())).isFalse();
        assertThat(documentChunkEmbeddingRepository.findByDocumentChunkDocumentId(doc.getId())).isEmpty();
        verify(vectorStoreService, never()).upsertChunkVectors(anyList());

        Files.deleteIfExists(corruptFilePath);
    }

    // =========================================================================
    // TEST 5: Qdrant indexing failure results in FAILED status (Never COMPLETED)
    // =========================================================================

    @Test
    @DisplayName("Qdrant indexing failure sets document status to FAILED and prevents COMPLETED status")
    void qdrantIndexingFailureResultsInFailedStatus() throws Exception {
        byte[] pdfBytes = createSamplePdfBytes("Qdrant Failure Test Content", 1);
        Path uploadDir = Paths.get("uploads-test").toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Path pdfPath = uploadDir.resolve("qdrant_fail_doc.pdf");
        Files.write(pdfPath, pdfBytes);

        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename("qdrant_fail_doc.pdf");
        doc.setStoredFilename("qdrant_fail_doc.pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize((long) pdfBytes.length);
        doc.setStoragePath(pdfPath.toString());
        doc.setStatus(DocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                doc.getId(),
                testUser.getId(),
                doc.getStoragePath(),
                doc.getOriginalFilename()
        );

        // Configure Qdrant to fail
        doThrow(new RuntimeException("Qdrant gRPC connection timeout"))
                .when(vectorStoreService).upsertChunkVectors(anyList());

        documentProcessingConsumer.consume(event);

        Document updatedDoc = documentRepository.findById(doc.getId()).orElseThrow();
        // Document MUST NOT become COMPLETED
        assertThat(updatedDoc.getStatus()).isEqualTo(DocumentStatus.FAILED);

        Files.deleteIfExists(pdfPath);
    }

    // =========================================================================
    // TEST 6: Duplicate Kafka event is idempotent
    // =========================================================================

    @Test
    @DisplayName("Consumer skips duplicate events for COMPLETED documents without creating duplicate records")
    void duplicateKafkaEventIsIdempotent() throws Exception {
        byte[] pdfBytes = createSamplePdfBytes("Idempotency Test Content", 1);
        Path uploadDir = Paths.get("uploads-test").toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Path pdfPath = uploadDir.resolve("idempotent_doc.pdf");
        Files.write(pdfPath, pdfBytes);

        Document doc = new Document();
        doc.setOwner(testUser);
        doc.setOriginalFilename("idempotent_doc.pdf");
        doc.setStoredFilename("idempotent_doc.pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize((long) pdfBytes.length);
        doc.setStoragePath(pdfPath.toString());
        doc.setStatus(DocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                doc.getId(),
                testUser.getId(),
                doc.getStoragePath(),
                doc.getOriginalFilename()
        );

        // First consume call
        documentProcessingConsumer.consume(event);
        assertThat(documentRepository.findById(doc.getId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(documentTextRepository.findByDocumentId(doc.getId())).isPresent();
        assertThat(documentChunkRepository.existsByDocumentId(doc.getId())).isTrue();
        assertThat(documentChunkEmbeddingRepository.findByDocumentChunkDocumentId(doc.getId())).hasSize(1);
        verify(vectorStoreService, times(1)).upsertChunkVectors(anyList());

        long initialTextRecordCount = documentTextRepository.count();
        long initialChunkRecordCount = documentChunkRepository.count();
        long initialEmbeddingRecordCount = documentChunkEmbeddingRepository.count();

        // Duplicate consume call
        documentProcessingConsumer.consume(event);

        // Verify status remains COMPLETED, record counts did not increase, and Qdrant upsert was not repeated
        assertThat(documentRepository.findById(doc.getId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(documentTextRepository.count()).isEqualTo(initialTextRecordCount);
        assertThat(documentChunkRepository.count()).isEqualTo(initialChunkRecordCount);
        assertThat(documentChunkEmbeddingRepository.count()).isEqualTo(initialEmbeddingRecordCount);
        verify(vectorStoreService, times(1)).upsertChunkVectors(anyList());

        Files.deleteIfExists(pdfPath);
    }

    private byte[] createSamplePdfBytes(String text, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                    contents.beginText();
                    contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contents.newLineAtOffset(100, 700);
                    contents.showText(text + " - Page " + i);
                    contents.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
