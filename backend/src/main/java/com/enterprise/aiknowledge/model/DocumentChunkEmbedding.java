package com.enterprise.aiknowledge.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing metadata for an embedding generated from a {@link DocumentChunk}.
 *
 * <p><strong>Architectural Decision:</strong><br>
 * Following production AI-platform design patterns, PostgreSQL stores <em>only embedding metadata</em>
 * (model name, dimensions, chunk relationship, timestamps). The high-dimensional float vector array
 * is stored in a dedicated vector database (Qdrant) during vector indexing to prevent database bloat.</p>
 */
@Entity
@Table(
        name = "document_chunk_embeddings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_doc_chunk_embedding", columnNames = {"document_chunk_id"})
        },
        indexes = {
                @Index(name = "idx_doc_chunk_embedding_chunk_id", columnList = "document_chunk_id")
        }
)
public class DocumentChunkEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Associated document chunk. One-to-one relationship enforced by unique constraint.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_chunk_id", nullable = false, unique = true)
    private DocumentChunk documentChunk;

    /**
     * Embedding model used (e.g., "gemini-embedding-2").
     */
    @Column(nullable = false, length = 100)
    private String model;

    /**
     * Vector dimensionality (e.g., 768).
     */
    @Column(nullable = false)
    private Integer dimensions;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public DocumentChunkEmbedding() {}

    public DocumentChunkEmbedding(DocumentChunk documentChunk, String model, Integer dimensions) {
        this.documentChunk = documentChunk;
        this.model = model;
        this.dimensions = dimensions;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public DocumentChunk getDocumentChunk() {
        return documentChunk;
    }

    public void setDocumentChunk(DocumentChunk documentChunk) {
        this.documentChunk = documentChunk;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
