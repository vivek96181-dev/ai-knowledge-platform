package com.enterprise.aiknowledge.controller;

import com.enterprise.aiknowledge.dto.DocumentResponse;
import com.enterprise.aiknowledge.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller for document management operations.
 *
 * <p>Base path: {@code /api/documents}</p>
 *
 * <p>Handles multipart PDF uploads, listing, fetching, and deleting documents.
 * All endpoints require authentication.</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Uploads a new PDF document.
     *
     * @param file           the uploaded PDF file (multipart/form-data)
     * @param authentication currently authenticated user
     * @return HTTP 201 Created with document metadata
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        String currentUserEmail = authentication.getName();
        DocumentResponse response = documentService.uploadDocument(file, currentUserEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists documents accessible to the current user.
     *
     * <p>USER role sees only their owned documents; ADMIN role sees all documents.</p>
     *
     * @param authentication currently authenticated user
     * @return HTTP 200 OK with list of document metadata
     */
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getAllDocuments(Authentication authentication) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = hasAdminRole(authentication);
        List<DocumentResponse> documents = documentService.getAllDocuments(currentUserEmail, isAdmin);
        return ResponseEntity.ok(documents);
    }

    /**
     * Retrieves a single document by ID.
     *
     * @param id             document primary key
     * @param authentication currently authenticated user
     * @return HTTP 200 OK with document metadata
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocumentById(
            @PathVariable Long id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = hasAdminRole(authentication);
        DocumentResponse document = documentService.getDocumentById(id, currentUserEmail, isAdmin);
        return ResponseEntity.ok(document);
    }

    /**
     * Deletes a document by ID and removes its physical file from storage.
     *
     * @param id             document primary key
     * @param authentication currently authenticated user
     * @return HTTP 200 OK with no body
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication.getName();
        boolean isAdmin = hasAdminRole(authentication);
        documentService.deleteDocument(id, currentUserEmail, isAdmin);
        return ResponseEntity.ok().build();
    }

    private boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_ADMIN"));
    }
}
