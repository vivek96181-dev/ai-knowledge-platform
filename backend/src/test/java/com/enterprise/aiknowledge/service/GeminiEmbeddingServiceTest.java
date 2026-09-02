package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests verifying {@link GeminiEmbeddingService} embedding generation, dimension validation,
 * bounded retries on transient errors, and batch processing.
 */
class GeminiEmbeddingServiceTest {

    private static final String TEST_MODEL = "gemini-embedding-2";
    private static final int TEST_DIMENSIONS = 768;
    private static final int TEST_BATCH_SIZE = 5;
    private static final int TEST_MAX_RETRIES = 3;
    private static final long TEST_RETRY_DELAY_MS = 10; // Fast retry for tests

    private List<Float> valid768Vector;

    @BeforeEach
    void setUp() {
        valid768Vector = new ArrayList<>(Collections.nCopies(TEST_DIMENSIONS, 0.05f));
    }

    @Test
    @DisplayName("Configuration validation rejects invalid parameters")
    void configurationValidation() {
        assertThatThrownBy(() -> new GeminiEmbeddingService(TEST_MODEL, 0, 10, 3, 100, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must be greater than 0");

        assertThatThrownBy(() -> new GeminiEmbeddingService(TEST_MODEL, 768, 0, 3, 100, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be > 0");

        assertThatThrownBy(() -> new GeminiEmbeddingService(TEST_MODEL, 768, 10, -1, 100, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max retries must be >= 0");
    }

    @Test
    @DisplayName("Successful embedding generation returns valid 768-dimension vector")
    void successfulEmbeddingGeneration() {
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, TEST_MAX_RETRIES, TEST_RETRY_DELAY_MS, "test-key",
                text -> valid768Vector
        );

        List<Float> result = service.generateEmbedding("Sample text for embedding");

        assertThat(result).hasSize(TEST_DIMENSIONS);
        assertThat(service.getModel()).isEqualTo(TEST_MODEL);
        assertThat(service.getDimensions()).isEqualTo(TEST_DIMENSIONS);
    }

    @Test
    @DisplayName("Dimension mismatch validation throws IllegalStateException")
    void dimensionMismatchThrowsException() {
        List<Float> invalidVector = new ArrayList<>(Collections.nCopies(512, 0.01f)); // 512 instead of 768

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, TEST_MAX_RETRIES, TEST_RETRY_DELAY_MS, "test-key",
                text -> invalidVector
        );

        assertThatThrownBy(() -> service.generateEmbedding("Some text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch")
                .hasMessageContaining("expected 768");
    }

    @Test
    @DisplayName("Transient error (e.g. 429 rate limit or timeout) retries and succeeds on subsequent attempt")
    void transientErrorRetriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, TEST_MAX_RETRIES, TEST_RETRY_DELAY_MS, "test-key",
                text -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new RuntimeException("429 RESOURCE_EXHAUSTED: Rate limit exceeded");
                    }
                    return valid768Vector;
                }
        );

        List<Float> result = service.generateEmbedding("Test retry");
        assertThat(result).hasSize(TEST_DIMENSIONS);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Transient error exceeding maxRetries fails with bounded retry exception")
    void transientErrorExceedingMaxRetriesFails() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, 2, TEST_RETRY_DELAY_MS, "test-key",
                text -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException(new IOException("Connection timeout to Gemini API"));
                }
        );

        assertThatThrownBy(() -> service.generateEmbedding("Test failure"))
                .isInstanceOf(RuntimeException.class);

        // Max retries = 2 -> 1 initial attempt + 2 retries = 3 attempts total
        assertThat(attempts.get()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Permanent client error (e.g. 400 Bad Request or 401 Unauthorized) fails immediately without retry")
    void permanentErrorFailsImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, 3, TEST_RETRY_DELAY_MS, "test-key",
                text -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("401 Unauthorized: Invalid API key provided");
                }
        );

        assertThatThrownBy(() -> service.generateEmbedding("Test unauthorized"))
                .isInstanceOf(RuntimeException.class);

        // Permanent error should NOT retry
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch embedding processes multiple chunks and maintains chunk ID mapping")
    void batchEmbeddingMaintainsChunkMapping() {
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, 2, TEST_MAX_RETRIES, TEST_RETRY_DELAY_MS, "test-key",
                text -> valid768Vector
        );

        Document doc = new Document();
        List<DocumentChunk> chunks = List.of(
                createDummyChunk(101L, doc, 0, 1, "Chunk 1 text"),
                createDummyChunk(102L, doc, 1, 1, "Chunk 2 text"),
                createDummyChunk(103L, doc, 2, 2, "Chunk 3 text")
        );

        Map<Long, List<Float>> resultMap = service.generateBatchEmbeddings(chunks);

        assertThat(resultMap).hasSize(3);
        assertThat(resultMap).containsKeys(101L, 102L, 103L);
        assertThat(resultMap.get(101L)).hasSize(TEST_DIMENSIONS);
        assertThat(resultMap.get(102L)).hasSize(TEST_DIMENSIONS);
        assertThat(resultMap.get(103L)).hasSize(TEST_DIMENSIONS);
    }

    @Test
    @DisplayName("Null or blank text throws IllegalArgumentException")
    void nullOrBlankTextThrowsException() {
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                TEST_MODEL, TEST_DIMENSIONS, TEST_BATCH_SIZE, TEST_MAX_RETRIES, TEST_RETRY_DELAY_MS, "test-key",
                text -> valid768Vector
        );

        assertThatThrownBy(() -> service.generateEmbedding(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.generateEmbedding("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DocumentChunk createDummyChunk(Long id, Document doc, int index, int page, String text) {
        DocumentChunk chunk = new DocumentChunk(doc, index, page, text, 0, text.length());
        try {
            var field = DocumentChunk.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(chunk, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return chunk;
    }
}
