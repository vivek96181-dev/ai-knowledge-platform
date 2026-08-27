package com.enterprise.aiknowledge.kafka;

import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Consumer component that processes {@link DocumentUploadedEvent} asynchronously from Kafka.
 *
 * <p><strong>Status state transitions:</strong>
 * <ul>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code COMPLETED} (normal success)</li>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code FAILED} (if missing file or unreadable)</li>
 * </ul>
 * </p>
 *
 * <p><strong>Idempotency Strategy:</strong><br>
 * If a duplicate Kafka message is consumed for a document that is already {@code COMPLETED},
 * processing is skipped immediately to prevent redundant work or state corruption.</p>
 */
@Component
public class DocumentProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingConsumer.class);

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;

    public DocumentProcessingConsumer(
            DocumentRepository documentRepository,
            FileStorageService fileStorageService) {
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
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

        // Step 2: Idempotency check — skip if already completed
        if (document.getStatus() == DocumentStatus.COMPLETED) {
            log.info("Document ID: {} is already COMPLETED. Skipping duplicate event.", document.getId());
            return;
        }

        try {
            // Step 3: Transition status to PROCESSING
            log.info("Transitioning document ID: {} status UPLOADED -> PROCESSING", document.getId());
            document.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(document);

            // Step 4 & 5: Verify physical file existence and readability
            if (!fileStorageService.fileExists(document.getStoragePath())) {
                log.error("Physical file does not exist at storagePath: {} for document ID: {}",
                        document.getStoragePath(), document.getId());
                document.setStatus(DocumentStatus.FAILED);
                documentRepository.save(document);
                return;
            }

            Path path = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();
            if (!Files.isReadable(path)) {
                log.error("Physical file is unreadable at storagePath: {} for document ID: {}",
                        document.getStoragePath(), document.getId());
                document.setStatus(DocumentStatus.FAILED);
                documentRepository.save(document);
                return;
            }

            // Verify file stream can be opened
            try (InputStream is = Files.newInputStream(path)) {
                if (is.readAllBytes().length == 0) {
                    log.error("Physical file is empty at storagePath: {} for document ID: {}",
                            document.getStoragePath(), document.getId());
                    document.setStatus(DocumentStatus.FAILED);
                    documentRepository.save(document);
                    return;
                }
            }

            // Step 6: Simulate processing completed (future phases will add text extraction & embeddings)
            log.info("Successfully processed document ID: {}. Transitioning PROCESSING -> COMPLETED", document.getId());
            document.setStatus(DocumentStatus.COMPLETED);
            documentRepository.save(document);

        } catch (Exception ex) {
            log.error("Unexpected processing error for document ID: {}", document.getId(), ex);
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
        }
    }
}
