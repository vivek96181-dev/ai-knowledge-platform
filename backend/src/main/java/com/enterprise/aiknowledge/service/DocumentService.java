package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.DocumentResponse;
import com.enterprise.aiknowledge.exception.InvalidFileException;
import com.enterprise.aiknowledge.exception.ResourceNotFoundException;
import com.enterprise.aiknowledge.kafka.DocumentEventProducer;
import com.enterprise.aiknowledge.kafka.DocumentUploadedEvent;
import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.model.User;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import com.enterprise.aiknowledge.repository.DocumentTextRepository;
import com.enterprise.aiknowledge.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Service layer for Document Management business logic.
 *
 * <p>Orchestrates document uploads, ownership validation, storage delegation,
 * database persistence, and asynchronous Kafka event publishing.</p>
 */
@Service
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentTextRepository documentTextRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final DocumentEventProducer documentEventProducer;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentTextRepository documentTextRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            DocumentEventProducer documentEventProducer) {
        this.documentRepository = documentRepository;
        this.documentTextRepository = documentTextRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.documentEventProducer = documentEventProducer;
    }

    /**
     * Uploads and stores a new PDF document and triggers background processing via Kafka.
     *
     * @param file             the uploaded multipart PDF file
     * @param currentUserEmail email of the authenticated user uploading the file
     * @return response DTO containing document metadata with status UPLOADED
     */
    public DocumentResponse uploadDocument(MultipartFile file, String currentUserEmail) {
        // Validate file presence
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File is required and must not be empty");
        }

        // Validate original filename
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidFileException("Filename must not be empty");
        }

        String cleanFilename = StringUtils.cleanPath(originalFilename);

        // Validate PDF type (MIME type and file extension)
        String contentType = file.getContentType();
        boolean isValidMime = contentType != null && (
                contentType.equalsIgnoreCase("application/pdf") ||
                contentType.equalsIgnoreCase("application/x-pdf")
        );
        boolean hasPdfExtension = cleanFilename.toLowerCase().endsWith(".pdf");

        if (!isValidMime || !hasPdfExtension) {
            throw new InvalidFileException("Invalid file type. Only PDF documents are allowed");
        }

        // Resolve authenticated user owner
        User owner = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        // Generate safe unique stored filename
        String storedFilename = UUID.randomUUID().toString() + "_" + cleanFilename;

        // Store file physically
        String storagePath = fileStorageService.storeFile(file, storedFilename);

        // Build entity with initial status UPLOADED
        Document document = new Document();
        document.setOwner(owner);
        document.setOriginalFilename(cleanFilename);
        document.setStoredFilename(storedFilename);
        document.setContentType(contentType);
        document.setFileSize(file.getSize());
        document.setStoragePath(storagePath);
        document.setStatus(DocumentStatus.UPLOADED);

        Document savedDocument = documentRepository.save(document);

        // Publish asynchronous Kafka event for background processing
        DocumentUploadedEvent event = new DocumentUploadedEvent(
                savedDocument.getId(),
                owner.getId(),
                storagePath,
                cleanFilename
        );
        documentEventProducer.sendDocumentUploadedEvent(event);

        return mapToResponse(savedDocument);
    }

    /**
     * Retrieves all documents accessible to the current user.
     *
     * <p>USER role receives only documents they own. ADMIN role receives all documents.</p>
     *
     * @param currentUserEmail email of the authenticated user
     * @param isAdmin          whether the authenticated user has ADMIN role
     * @return list of document responses
     */
    public List<DocumentResponse> getAllDocuments(String currentUserEmail, boolean isAdmin) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        List<Document> documents = isAdmin
                ? documentRepository.findAll()
                : documentRepository.findByOwnerId(currentUser.getId());

        return documents.stream()
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * Retrieves a single document by ID with ownership enforcement.
     *
     * @param id               document primary key
     * @param currentUserEmail email of the authenticated user
     * @param isAdmin          whether the authenticated user has ADMIN role
     * @return document response
     */
    public DocumentResponse getDocumentById(Long id, String currentUserEmail, boolean isAdmin) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + id));

        // Server-side ownership enforcement
        if (!isAdmin && !document.getOwner().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Access denied: You do not own this document");
        }

        return mapToResponse(document);
    }

    /**
     * Deletes a document by ID with ownership enforcement.
     * Deletes both metadata from PostgreSQL and the physical file from disk.
     *
     * @param id               document primary key
     * @param currentUserEmail email of the authenticated user
     * @param isAdmin          whether the authenticated user has ADMIN role
     */
    public void deleteDocument(Long id, String currentUserEmail, boolean isAdmin) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + currentUserEmail));

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + id));

        // Server-side ownership enforcement
        if (!isAdmin && !document.getOwner().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Access denied: You do not own this document");
        }

        // Delete physical file from storage
        fileStorageService.deleteFile(document.getStoragePath());

        // Delete associated extracted text if present
        documentTextRepository.deleteByDocumentId(document.getId());

        // Delete metadata row from database
        documentRepository.delete(document);
    }

    private DocumentResponse mapToResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getOwner().getId(),
                document.getOwner().getEmail()
        );
    }
}
