package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.RerankCandidate;
import com.enterprise.aiknowledge.dto.RerankedCandidate;
import com.enterprise.aiknowledge.exception.RerankerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Production implementation of {@link Reranker} using Google's Gemini models via the GenAI SDK.
 *
 * <p><strong>Key Design Principles:</strong>
 * <ul>
 *   <li>Single-batch prompt scoring to eliminate N+1 API calls.</li>
 *   <li>Normalized relevance scoring on a continuous [0.0, 1.0] scale.</li>
 *   <li>Deterministic tie-breaking: primary score DESC, secondary chunkId ASC.</li>
 *   <li>Bounded timeout and robust JSON parsing with markdown code-block stripping.</li>
 *   <li>Strict privacy guard: never logs prompts, chunk texts, user queries, or API credentials.</li>
 *   <li>Pluggable {@link RawRerankInvoker} enabling 100% offline, deterministic automated tests.</li>
 * </ul>
 * </p>
 */
@Service
public class GeminiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(GeminiReranker.class);

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final long timeoutMs;
    private final ObjectMapper objectMapper;
    private final RawRerankInvoker rawInvoker;

    @FunctionalInterface
    public interface RawRerankInvoker {
        String invoke(String prompt) throws Exception;
    }

    @Autowired
    public GeminiReranker(
            @Value("${search.reranking.enabled:true}") boolean enabled,
            @Value("${search.reranking.model:gemini-2.5-flash}") String model,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${search.reranking.timeout-ms:5000}") long timeoutMs,
            ObjectMapper objectMapper) {
        this(enabled, model, apiKey, timeoutMs, objectMapper, null);
    }

    public GeminiReranker(
            boolean enabled,
            String model,
            String apiKey,
            long timeoutMs,
            ObjectMapper objectMapper,
            RawRerankInvoker customInvoker) {
        this.enabled = enabled;
        this.model = (model != null && !model.isBlank()) ? model : "gemini-2.5-flash";
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 5000L;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();

        if (customInvoker != null) {
            this.rawInvoker = customInvoker;
        } else {
            this.rawInvoker = this::callGeminiApiDirectly;
        }
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be blank for reranking");
        }
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }

        long startTime = System.currentTimeMillis();
        int candidateCount = candidates.size();

        log.info("Starting reranking with model '{}' for {} candidates (requested topK: {})",
                model, candidateCount, topK);

        String prompt = buildPrompt(query, candidates);

        String responseText;
        try {
            responseText = executeWithTimeout(prompt);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Reranking failed after {} ms: {}", latency, e.getMessage());
            throw new RerankerException("Failed to rerank candidates: " + e.getMessage(), e);
        }

        Map<Long, Float> scoreMap = parseScores(responseText);

        List<RerankedCandidate> reranked = new ArrayList<>(candidates.size());
        for (RerankCandidate candidate : candidates) {
            float score = scoreMap.getOrDefault(candidate.chunkId(), 0.0f);
            // Clamp score between 0.0 and 1.0
            float clampedScore = Math.max(0.0f, Math.min(1.0f, score));
            reranked.add(new RerankedCandidate(candidate, clampedScore));
        }

        // Sort descending by rerankScore, then ascending by chunkId for deterministic tie-breaking
        reranked.sort(Comparator
                .comparingDouble((RerankedCandidate rc) -> (double) rc.rerankScore())
                .reversed()
                .thenComparingLong(rc -> rc.candidate().chunkId()));

        int boundedSize = Math.min(topK, reranked.size());
        List<RerankedCandidate> finalTopK = reranked.subList(0, boundedSize);

        long latency = System.currentTimeMillis() - startTime;
        log.info("Reranking completed successfully: {} candidates -> {} top-K results in {} ms",
                candidateCount, finalTopK.size(), latency);

        return finalTopK;
    }

    private String buildPrompt(String query, List<RerankCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert search relevance reranker. Evaluate how relevant each candidate chunk is to the search query.\n");
        sb.append("Assign each chunk a relevance score between 0.00 (completely irrelevant) and 1.00 (directly answers the query).\n\n");
        sb.append("Query: ").append(query.trim()).append("\n\n");
        sb.append("Candidates:\n");

        for (RerankCandidate c : candidates) {
            sb.append("--- Chunk ID: ").append(c.chunkId()).append(" ---\n");
            sb.append(c.text() != null ? c.text().trim() : "").append("\n\n");
        }

        sb.append("Respond ONLY with a JSON array of objects containing 'id' and 'score'. Example:\n");
        sb.append("[{\"id\": 101, \"score\": 0.95}, {\"id\": 102, \"score\": 0.30}]\n");

        return sb.toString();
    }

    private String executeWithTimeout(String prompt) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> rawInvoker.invoke(prompt));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RerankerException("Reranker model call timed out after " + timeoutMs + " ms", te);
        } finally {
            executor.shutdownNow();
        }
    }

    private String callGeminiApiDirectly(String prompt) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured for reranking");
        }

        Client client = Client.builder().apiKey(apiKey).build();
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        if (response == null || response.text() == null) {
            throw new RerankerException("Received null response from Gemini reranker API");
        }
        return response.text();
    }

    private Map<Long, Float> parseScores(String responseText) {
        Map<Long, Float> scoreMap = new HashMap<>();
        if (responseText == null || responseText.isBlank()) {
            return scoreMap;
        }

        String cleaned = responseText.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (item.has("id") && item.has("score")) {
                        long id = item.get("id").asLong();
                        float score = (float) item.get("score").asDouble();
                        scoreMap.put(id, score);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse reranker JSON response: {}. Response snippet: {}",
                    e.getMessage(), cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned);
            throw new RerankerException("Malformed JSON from reranker model: " + e.getMessage(), e);
        }

        return scoreMap;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getModelName() {
        return model;
    }
}
