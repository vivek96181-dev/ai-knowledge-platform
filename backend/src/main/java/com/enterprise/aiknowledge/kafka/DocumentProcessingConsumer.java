package com.enterprise.aiknowledge.kafka;

import com.enterprise.aiknowledge.model.*;
import com.enterprise.aiknowledge.repository.DocumentChunkEmbeddingRepository;
import com.enterprise.aiknowledge.repository.DocumentChunkRepository;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.service.ChunkVectorDto;
import com.enterprise.aiknowledge.service.ChunkingService;
import com.enterprise.aiknowledge.service.EmbeddingService;
import com.enterprise.aiknowledge.service.FileStorageService;
import com.enterprise.aiknowledge.service.PdfExtractionResult;
import com.enterprise.aiknowledge.service.PdfTextExtractionService;
import com.enterprise.aiknowledge.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consumer component that receives {@link DocumentUploadedEvent} from Kafka,
 * extracts text via Apache PDFBox, persists the {@link DocumentText} entity,
 * chunks the text via {@link ChunkingService}, persists {@link DocumentChunk} entities,
 * generates vector embeddings via {@link EmbeddingService} (Gemini Embedding 2),
 * persists {@link DocumentChunkEmbedding} metadata in PostgreSQL,
 * and upserts high-dimensional vector points into the Qdrant vector database.
 *
 * <p><strong>Status Transitions:</strong>
 * <ul>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code COMPLETED} (on successful text extraction, chunking, embedding & vector indexing)</li>
 *   <li>{@code UPLOADED} → {@code PROCESSING} → {@code FAILED} (if any phase, including Qdrant indexing, fails)</li>
 * </ul>
 * </p>
 *
 * <p><strong>Idempotency Strategy:</strong><br>
 * If a duplicate event arrives for a document that is already {@code COMPLETED} with text, chunks, and matching embeddings,
 * processing is skipped immediately. If reprocessing is triggered, old Qdrant vectors, embeddings, and chunks are cleanly cleared first.</p>
 */
@Component
public class DocumentProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingConsumer.class);

    private final DocumentRepository documentRepository;
    private final DocumentTextRepository documentTextRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentChunkEmbeddingRepository documentChunkEmbeddingRepository;
    private final FileStorageService fileStorageService;
    private final PdfTextExtractionService pdfTextExtractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    public DocumentProcessingConsumer(
            DocumentRepository documentRepository,
            DocumentTextRepository documentTextRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentChunkEmbeddingRepository documentChunkEmbeddingRepository,
            FileStorageService fileStorageService,
            PdfTextExtractionService pdfTextExtractionService,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService) {
        this.documentRepository = documentRepository;
        this.documentTextRepository = documentTextRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentChunkEmbeddingRepository = documentChunkEmbeddingRepository;
        this.fileStorageService = fileStorageService;
        this.pdfTextExtractionService = pdfTextExtractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
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

        // Step 2: Idempotency check — skip if COMPLETED and text, chunks, and embeddings already exist
        if (document.getStatus() == DocumentStatus.COMPLETED &&
                documentTextRepository.findByDocumentId(document.getId()).isPresent() &&
                documentChunkRepository.existsByDocumentId(document.getId())) {
            List<DocumentChunkEmbedding> existingEmbeddings =
                    documentChunkEmbeddingRepository.findByDocumentChunkDocumentId(document.getId());
            long totalChunks = documentChunkRepository.countByDocumentId(document.getId());

            // Check if all chunks have embeddings with matching model & dimensions
            boolean allEmbeddedWithSameConfig = totalChunks > 0 &&
                    existingEmbeddings.size() == totalChunks &&
                    existingEmbeddings.stream().allMatch(e ->
                            e.getModel().equals(embeddingService.getModel()) &&
                            e.getDimensions() == embeddingService.getDimensions());

            if (allEmbeddedWithSameConfig) {
                log.info("Document ID: {} is already COMPLETED with chunks and embeddings matching current model ({}) and dimensions ({}). Skipping duplicate event.",
                        document.getId(), embeddingService.getModel(), embeddingService.getDimensions());
                return;
            }
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

            // Step 5: Extract text per page using PDFBox
            log.info("Extracting PDF text for document ID: {} from path: {}", document.getId(), path);
            PdfExtractionResult extractionResult = pdfTextExtractionService.extractText(path);

            // Step 6: Persist or update DocumentText entity
            DocumentText documentText = documentTextRepository.findByDocumentId(document.getId())
                    .orElseGet(() -> new DocumentText(document, extractionResult.extractedText(), extractionResult.pageCount()));

            documentText.setExtractedText(extractionResult.extractedText());
            documentText.setPageCount(extractionResult.pageCount());
            documentTextRepository.save(documentText);

            // Step 7: Clear old Qdrant vectors, embeddings, and chunks if reprocessing
            vectorStoreService.deleteVectorsByDocumentId(document.getId());
            documentChunkEmbeddingRepository.deleteByDocumentChunkDocumentId(document.getId());
            documentChunkRepository.deleteByDocumentId(document.getId());

            List<DocumentChunk> chunks = chunkingService.chunkDocument(document, extractionResult.pages());
            if (!chunks.isEmpty()) {
                chunks = documentChunkRepository.saveAll(chunks);
                log.info("Saved {} chunks for document ID: {}", chunks.size(), document.getId());

                // Step 8: Generate vector embeddings for all chunks via EmbeddingService
                log.info("Generating embeddings for {} chunks of document ID: {} using model: {}",
                        chunks.size(), document.getId(), embeddingService.getModel());
                Map<Long, List<Float>> vectorMap = embeddingService.generateBatchEmbeddings(chunks);

                List<DocumentChunkEmbedding> embeddingsToSave = new ArrayList<>();
                List<ChunkVectorDto> chunkVectorsToIndex = new ArrayList<>();
                Long ownerId = document.getOwner() != null ? document.getOwner().getId() : null;

                for (DocumentChunk chunk : chunks) {
                    List<Float> vector = vectorMap.get(chunk.getId());
                    if (vector == null) {
                        throw new IllegalStateException("Missing embedding vector for chunk ID: " + chunk.getId());
                    }
                    embeddingsToSave.add(new DocumentChunkEmbedding(
                            chunk,
                            embeddingService.getModel(),
                            embeddingService.getDimensions()
                    ));
                    chunkVectorsToIndex.add(new ChunkVectorDto(
                            chunk.getId(),
                            document.getId(),
                            chunk.getPageNumber(),
                            chunk.getChunkIndex(),
                            ownerId,
                            vector
                    ));
                }

                // Persist embedding metadata in PostgreSQL
                documentChunkEmbeddingRepository.saveAll(embeddingsToSave);
                log.info("Saved {} embedding metadata records in PostgreSQL for document ID: {}",
                        embeddingsToSave.size(), document.getId());

                // Step 9: Upsert vector points with payload into Qdrant
                log.info("Upserting {} vector points into Qdrant collection '{}' for document ID: {}",
                        chunkVectorsToIndex.size(), vectorStoreService.getCollectionName(), document.getId());
                vectorStoreService.upsertChunkVectors(chunkVectorsToIndex);
                log.info("Successfully indexed all vectors in Qdrant for document ID: {}", document.getId());
            } else {
                log.warn("Document ID: {} has no extractable text chunks", document.getId());
            }

            // Step 10: Update document status to COMPLETED
            log.info("Successfully processed document ID: {} (pages: {}, chunks: {}). Status -> COMPLETED",
                    document.getId(), extractionResult.pageCount(), chunks.size());
            document.setStatus(DocumentStatus.COMPLETED);
            documentRepository.save(document);

        } catch (Exception ex) {
            log.error("Failed to process document text extraction, chunking, embedding, or Qdrant indexing for document ID: {}",
                    document.getId(), ex);
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
        }
    }
}
