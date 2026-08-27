package com.enterprise.aiknowledge.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing a document uploaded by a user.
 *
 * <p><strong>Table mapping:</strong> Maps to the {@code documents} table in PostgreSQL.</p>
 *
 * <p><strong>Ownership:</strong> Each document belongs to a single {@link User} owner.
 * User ownership is enforced strictly server-side.</p>
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user who uploaded and owns this document.
     * Foreign key column: {@code owner_id}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * Original filename as provided by the client during upload.
     */
    @Column(nullable = false)
    private String originalFilename;

    /**
     * Unique name assigned to the file when saved on physical storage.
     * Prevents collisions and path traversal attacks.
     */
    @Column(nullable = false, unique = true)
    private String storedFilename;

    /**
     * MIME type of the file (e.g. "application/pdf").
     */
    @Column(nullable = false)
    private String contentType;

    /**
     * Size of the file in bytes.
     */
    @Column(nullable = false)
    private Long fileSize;

    /**
     * Absolute or relative path to where the file is stored on disk/cloud.
     */
    @Column(nullable = false)
    private String storagePath;

    /**
     * Current status of the document (UPLOADED, PROCESSING, COMPLETED, FAILED).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    /**
     * Timestamp of when this document was created.
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of when this document record was last updated.
     */
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Document() {}

    // Getters
    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredFilename() {
        return storedFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // Setters
    public void setOwner(User owner) {
        this.owner = owner;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public void setStoredFilename(String storedFilename) {
        this.storedFilename = storedFilename;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }
}
