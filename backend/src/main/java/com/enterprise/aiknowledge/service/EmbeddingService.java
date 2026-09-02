package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.model.DocumentChunk;

import java.util.List;
import java.util.Map;

/**
 * Service interface for generating high-dimensional vector embeddings from text chunks.
 *
 * <p><strong>Architectural Rationale:</strong><br>
 * Isolates the embedding provider (e.g., Google Gemini) behind a clean abstraction.
 * This allows swapping providers (such as local Sentence Transformers or ONNX models)
 * without altering downstream worker or ingestion logic.</p>
 */
public interface EmbeddingService {

    /**
     * Generates a vector embedding for a single text input.
     *
     * @param text plain text to embed
     * @return list of floating-point values representing the vector embedding
     */
    List<Float> generateEmbedding(String text);

    /**
     * Generates embeddings in batches for multiple document chunks, preserving the mapping from
     * chunk ID to its embedding vector.
     *
     * @param chunks list of document chunks to embed
     * @return map of DocumentChunk ID to its embedding vector
     */
    Map<Long, List<Float>> generateBatchEmbeddings(List<DocumentChunk> chunks);

    /**
     * Returns the name of the active embedding model (e.g., "gemini-embedding-2").
     */
    String getModel();

    /**
     * Returns the configured vector dimensionality (e.g., 768).
     */
    int getDimensions();
}
