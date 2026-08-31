package com.enterprise.aiknowledge.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing extracted text content from a PDF document.
 *
 * <p><strong>Database Mapping:</strong> Stored in a separate table {@code document_texts}
 * to keep basic document metadata queries lightweight.</p>
 */
@Entity
@Table(name = "document_texts")
public class DocumentText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Associated document entity. Unique foreign key constraint on {@code document_id}.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;

    /**
     * Extracted plain text content from the PDF file.
     */
    @Lob
    @Column(nullable = false)
    private String extractedText;

    /**
     * Number of pages in the PDF document.
     */
    @Column(nullable = false)
    private Integer pageCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public DocumentText() {}

    public DocumentText(Document document, String extractedText, Integer pageCount) {
        this.document = document;
        this.extractedText = extractedText;
        this.pageCount = pageCount;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // Setters
    public void setDocument(Document document) {
        this.document = document;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }
}
