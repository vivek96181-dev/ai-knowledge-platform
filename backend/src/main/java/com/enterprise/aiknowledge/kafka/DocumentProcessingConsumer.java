package com.enterprise.aiknowledge.kafka;

import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.model.DocumentText;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.service.FileStorageService;
import com.enterprise.aiknowledge.service.PdfExtractionResult;
import com.enterprise.aiknowledge.service.PdfTextExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Consumer component that receives {@link DocumentUploadedEvent} from Kafka, extracts text via Apache PDFBox,
 * and persists the resulting {@link DocumentText} entity in PostgreSQL.
 *
 * <p><strong>Status Transitions:</strong>
 * <ul>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code COMPLETED} (on successful text extraction)</li>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code FAILED} (if file missing, unreadable, or corrupt PDF)</li>
 * </ul>
 * </p>
 *
 * <p><strong>Idempotency Strategy:</strong><br>
 * If a duplicate event arrives for a document that is already {@code COMPLETED} and has an associated
 * {@link DocumentText} record, processing is skipped immediately to avoid duplicate database rows.</p>
 */
@Component
public class DocumentProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingConsumer.class);

    private final DocumentRepository documentRepository;
    private final DocumentTextRepository documentTextRepository;
    private final FileStorageService fileStorageService;
    private final PdfTextExtractionService pdfTextExtractionService;

    public DocumentProcessingConsumer(
            DocumentRepository documentRepository,
            DocumentTextRepository documentTextRepository,
            FileStorageService fileStorageService,
            PdfTextExtractionService pdfTextExtractionService) {
        this.documentRepository = documentRepository;
        this.documentTextRepository = documentTextRepository;
        this.fileStorageService = fileStorageService;
        this.pdfTextExtractionService = pdfTextExtractionService;
    }

    @KafkaListener(
            topics = "${kafka.topic.document-uploaded:document-uploaded}",
            groupId = "${spring.kafka.consumer.group-id:ai-knowledge-platform-group}"
    )
    @Transactional
    public void consume(DocumentUploadedEvent event) {
        log.info("Received DocumentUploadedEvent for document ID: {}, storagePath: {}",
                event.documentId(), event.storagePath());

        // Step 1: Find document by ID
        Optional<Document> documentOpt = documentRepository.findById(event.documentId());
        if (documentOpt.isEmpty()) {
            log.warn("Document not found with ID: {}. Skipping event.", event.documentId());
            return;
        }

        Document document = documentOpt.get();

        // Step 2: Idempotency check — skip if COMPLETED and DocumentText already exists
        if (document.getStatus() == DocumentStatus.COMPLETED &&
                documentTextRepository.findByDocumentId(document.getId()).isPresent()) {
            log.info("Document ID: {} is already COMPLETED with extracted text present. Skipping duplicate event.", document.getId());
            return;
        }

        try {
            // Step 3: Transition status UPLOADED -> PROCESSING
            log.info("Transitioning document ID: {} status UPLOADED -> PROCESSING", document.getId());
            document.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(document);

            // Step 4: Verify physical file existence
            if (!fileStorageService.fileExists(document.getStoragePath())) {
                log.error("Physical file does not exist at storagePath: {} for document ID: {}",
                        document.getStoragePath(), document.getId());
                document.setStatus(DocumentStatus.FAILED);
                documentRepository.save(document);
                return;
            }

            Path path = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();

            // Step 5: Extract text using PDFBox
            log.info("Extracting PDF text for document ID: {} from path: {}", document.getId(), path);
            PdfExtractionResult extractionResult = pdfTextExtractionService.extractText(path);

            // Step 6: Persist or update DocumentText entity
            DocumentText documentText = documentTextRepository.findByDocumentId(document.getId())
                    .orElseGet(() -> new DocumentText(document, extractionResult.extractedText(), extractionResult.pageCount()));

            documentText.setExtractedText(extractionResult.extractedText());
            documentText.setPageCount(extractionResult.pageCount());
            documentTextRepository.save(documentText);

            // Step 7: Update document status to COMPLETED
            log.info("Successfully extracted text (pages: {}) and persisted DocumentText for document ID: {}. Status -> COMPLETED",
                    extractionResult.pageCount(), document.getId());
            document.setStatus(DocumentStatus.COMPLETED);
            documentRepository.save(document);

        } catch (Exception ex) {
            log.error("Failed to process document text extraction for document ID: {}", document.getId(), ex);
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
        }
    }
}
