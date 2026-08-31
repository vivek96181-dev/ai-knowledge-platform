package com.enterprise.aiknowledge.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing a searchable segment (chunk) of an uploaded document.
 *
 * <p><strong>Citation Metadata:</strong> Stores {@code pageNumber}, {@code characterStart},
 * and {@code characterEnd} to enable precise source citations in the future RAG retrieval phase
 * (e.g., "Source: Unit-5-ACD.pdf, Page 7").</p>
 *
 * <p><strong>Database Constraints:</strong> Enforces uniqueness on {@code (document_id, chunk_index)}
 * to guarantee deterministic, non-duplicate chunk ordering per document.</p>
 */
@Entity
@Table(
        name = "document_chunks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_doc_chunk_index", columnNames = {"document_id", "chunk_index"})
        },
        indexes = {
                @Index(name = "idx_doc_chunk_doc_id", columnList = "document_id"),
                @Index(name = "idx_doc_chunk_doc_id_idx", columnList = "document_id, chunk_index")
        }
)
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Associated parent document.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /**
     * Zero-based sequential index of this chunk within the document (0, 1, 2, ...).
     */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /**
     * 1-based page number where this text originated in the source PDF.
     */
    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    /**
     * Text content of this chunk.
     */
    @Lob
    @Column(nullable = false)
    private String text;

    /**
     * Character offset start position within the original source page text.
     */
    @Column(name = "character_start", nullable = false)
    private Integer characterStart;

    /**
     * Character offset end position within the original source page text.
     */
    @Column(name = "character_end", nullable = false)
    private Integer characterEnd;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public DocumentChunk() {}

    public DocumentChunk(
            Document document,
            Integer chunkIndex,
            Integer pageNumber,
            String text,
            Integer characterStart,
            Integer characterEnd) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.pageNumber = pageNumber;
        this.text = text;
        this.characterStart = characterStart;
        this.characterEnd = characterEnd;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getText() {
        return text;
    }

    public Integer getCharacterStart() {
        return characterStart;
    }

    public Integer getCharacterEnd() {
        return characterEnd;
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

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setCharacterStart(Integer characterStart) {
        this.characterStart = characterStart;
    }

    public void setCharacterEnd(Integer characterEnd) {
        this.characterEnd = characterEnd;
    }
}
