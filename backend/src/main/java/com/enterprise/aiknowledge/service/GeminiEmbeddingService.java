package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.model.DocumentChunk;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Implementation of {@link EmbeddingService} using Google's official GenAI SDK (Gemini Embedding 2).
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Configurable model (default {@code gemini-embedding-2}) and output dimensions (default {@code 768})</li>
 *   <li>Bounded retry logic for transient failures (HTTP 429 rate limits, 503, network timeouts)</li>
 *   <li>Immediate failure on permanent client errors (400, 401/403)</li>
 *   <li>Strict validation ensuring returned vectors match expected dimensionality</li>
 *   <li>Strict privacy guard: never logs document chunk text or API keys</li>
 * </ul>
 * </p>
 */
@Service
public class GeminiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingService.class);

    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxRetries;
    private final long retryDelayMs;
    private final String apiKey;

    /**
     * Functional embedding invoker for the raw API call, allowing clean mock injection during unit tests.
     */
    private final Function<String, List<Float>> rawApiCall;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiEmbeddingService(
            @Value("${gemini.embedding.model:gemini-embedding-2}") String model,
            @Value("${gemini.embedding.dimensions:768}") int dimensions,
            @Value("${gemini.embedding.batch-size:10}") int batchSize,
            @Value("${gemini.embedding.max-retries:3}") int maxRetries,
            @Value("${gemini.embedding.retry-delay-ms:500}") long retryDelayMs,
            @Value("${gemini.api-key:}") String apiKey) {
        this(model, dimensions, batchSize, maxRetries, retryDelayMs, apiKey, null);
    }

    public GeminiEmbeddingService(
            String model,
            int dimensions,
            int batchSize,
            int maxRetries,
            long retryDelayMs,
            String apiKey,
            Function<String, List<Float>> customEmbeddingFunction) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be greater than 0, but was: " + dimensions);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must be >= 0, but was: " + maxRetries);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be > 0, but was: " + batchSize);
        }

        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.apiKey = apiKey;

        if (customEmbeddingFunction != null) {
            this.rawApiCall = customEmbeddingFunction;
        } else {
            this.rawApiCall = this::callGeminiApiDirectly;
        }
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Cannot generate embedding for null or blank text");
        }
        long startTime = System.currentTimeMillis();
        List<Float> vector = callApiWithRetry(text);
        validateDimensions(vector);
        long duration = System.currentTimeMillis() - startTime;
        log.debug("Generated embedding with {} dimensions in {}ms", vector.size(), duration);
        return vector;
    }

    @Override
    public Map<Long, List<Float>> generateBatchEmbeddings(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<Float>> resultMap = new LinkedHashMap<>();
        log.info("Starting batch embedding generation for {} chunks using model: {}, dimensions: {}, batchSize: {}",
                chunks.size(), model, dimensions, batchSize);

        // Process in batches respecting configured batchSize
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int toIndex = Math.min(i + batchSize, chunks.size());
            List<DocumentChunk> currentBatch = chunks.subList(i, toIndex);

            log.info("Processing embedding batch [{}/{}] with {} chunks",
                    (i / batchSize) + 1, (int) Math.ceil((double) chunks.size() / batchSize), currentBatch.size());

            for (DocumentChunk chunk : currentBatch) {
                try {
                    List<Float> vector = generateEmbedding(chunk.getText());
                    resultMap.put(chunk.getId(), vector);
                } catch (Exception ex) {
                    log.error("Failed to generate embedding for document chunk ID: {}", chunk.getId(), ex);
                    throw ex;
                }
            }
        }

        log.info("Completed embedding generation for all {} chunks", resultMap.size());
        return resultMap;
    }

    /**
     * Executes the raw API call with bounded retries for transient errors.
     */
    private List<Float> callApiWithRetry(String text) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                return rawApiCall.apply(text);
            } catch (Exception ex) {
                lastException = ex;
                if (!isTransientError(ex) || attempt > maxRetries) {
                    log.error("Permanent error or max retries exceeded ({}/{}) calling Gemini Embedding API: {}",
                            attempt, maxRetries + 1, ex.getMessage());
                    throw new RuntimeException("Gemini Embedding API call failed: " + ex.getMessage(), ex);
                }

                long backoff = retryDelayMs * attempt;
                log.warn("Transient error on attempt {}/{}: {}. Retrying in {}ms...",
                        attempt, maxRetries + 1, ex.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Embedding retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException("Failed to generate embedding after retries", lastException);
    }

    /**
     * Direct call to Gemini Embedding API using Google Gen AI SDK.
     */
    private List<Float> callGeminiApiDirectly(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured. Please set GEMINI_API_KEY.");
        }

        Client client = Client.builder().apiKey(apiKey).build();
        EmbedContentConfig config = EmbedContentConfig.builder()
                .outputDimensionality(dimensions)
                .build();

        EmbedContentResponse response = client.models.embedContent(model, text, config);
        return extractValuesFromResponse(response);
    }

    /**
     * Extracts float vector values from Gemini {@link EmbedContentResponse}.
     */
    private List<Float> extractValuesFromResponse(EmbedContentResponse response) {
        if (response == null) {
            throw new IllegalStateException("Gemini returned null EmbedContentResponse");
        }

        // Extract values from embeddings list
        if (response.embeddings().isPresent() && !response.embeddings().get().isEmpty()) {
            com.google.genai.types.ContentEmbedding first = response.embeddings().get().get(0);
            if (first != null && first.values().isPresent()) {
                return first.values().get();
            }
        }

        throw new IllegalStateException("Gemini response did not contain embedding vector values");
    }

    /**
     * Determines whether an exception represents a transient failure eligible for retry.
     */
    private boolean isTransientError(Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        // Rate limit (429 / RESOURCE_EXHAUSTED)
        if (msg.contains("429") || msg.contains("resource_exhausted") || msg.contains("quota")) {
            return true;
        }

        // Temporary server errors (500, 502, 503, 504)
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("unavailable") || msg.contains("deadline")) {
            return true;
        }

        // Network / I/O issues
        if (ex instanceof IOException || msg.contains("timeout") || msg.contains("connection reset")) {
            return true;
        }

        // Permanent client errors: 400 Bad Request, 401/403 Unauthorized/Forbidden
        return false;
    }

    /**
     * Validates that the returned vector matches the expected dimensionality.
     */
    private void validateDimensions(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalStateException("Embedding vector is null or empty");
        }
        if (vector.size() != dimensions) {
            throw new IllegalStateException(String.format(
                    "Embedding dimension mismatch: expected %d dimensions, but received %d from model '%s'",
                    dimensions, vector.size(), model));
        }
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }
}
