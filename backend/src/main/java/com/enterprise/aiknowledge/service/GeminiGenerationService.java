package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.exception.GenerationServiceException;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Implementation of {@link GenerationService} using Google's official GenAI SDK (Gemini).
 *
 * <p><strong>Key Design Principles:</strong>
 * <ul>
 *   <li>Strict separation of System Instruction, Retrieved Context, and User Question.</li>
 *   <li>Configurable model, temperature, max output tokens, and bounded retry policy.</li>
 *   <li>Strict privacy guard: never logs document text, raw queries, generated text, or API keys.</li>
 *   <li>Pluggable functional invoker for 100% offline, deterministic unit testing.</li>
 * </ul>
 * </p>
 */
@Service
public class GeminiGenerationService implements GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GeminiGenerationService.class);

    private final String model;
    private final float temperature;
    private final int maxOutputTokens;
    private final int maxRetries;
    private final long retryDelayMs;
    private final String apiKey;

    /**
     * Functional invoker for the raw SDK call, allowing clean mock injection in tests.
     */
    private final RawGenerationInvoker rawGenerationInvoker;

    @FunctionalInterface
    public interface RawGenerationInvoker {
        String generate(String systemInstruction, String context, String question) throws Exception;
    }

    @Autowired
    public GeminiGenerationService(
            @Value("${gemini.generation.model:gemini-2.5-flash}") String model,
            @Value("${gemini.generation.temperature:0.2}") float temperature,
            @Value("${gemini.generation.max-output-tokens:1024}") int maxOutputTokens,
            @Value("${gemini.generation.max-retries:3}") int maxRetries,
            @Value("${gemini.generation.retry-delay-ms:500}") long retryDelayMs,
            @Value("${gemini.api-key:}") String apiKey) {
        this(model, temperature, maxOutputTokens, maxRetries, retryDelayMs, apiKey, null);
    }

    public GeminiGenerationService(
            String model,
            float temperature,
            int maxOutputTokens,
            int maxRetries,
            long retryDelayMs,
            String apiKey,
            RawGenerationInvoker customInvoker) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Generation model cannot be blank");
        }
        if (temperature < 0.0f || temperature > 2.0f) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0, but was: " + temperature);
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Max output tokens must be > 0, but was: " + maxOutputTokens);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must be >= 0, but was: " + maxRetries);
        }

        this.model = model;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.apiKey = apiKey;

        if (customInvoker != null) {
            this.rawGenerationInvoker = customInvoker;
        } else {
            this.rawGenerationInvoker = this::callGeminiApiDirectly;
        }
    }

    @Override
    public String generateAnswer(String systemInstruction, String context, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be null or blank");
        }
        if (context == null) {
            context = "";
        }

        long startTime = System.currentTimeMillis();
        log.info("Starting Gemini text generation (model: {}, temperature: {}, contextLength: {} chars)",
                model, temperature, context.length());

        String answer = callApiWithRetry(systemInstruction, context, question);

        if (answer == null || answer.trim().isBlank()) {
            log.error("Gemini model returned empty or blank text response");
            throw new GenerationServiceException("Gemini generation returned an empty or blank response");
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Gemini text generation succeeded in {}ms (answer length: {} chars)", duration, answer.length());
        return answer.trim();
    }

    /**
     * Executes the raw API call with bounded retries for transient errors.
     */
    private String callApiWithRetry(String systemInstruction, String context, String question) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                return rawGenerationInvoker.generate(systemInstruction, context, question);
            } catch (Exception ex) {
                lastException = ex;
                if (!isTransientError(ex) || attempt > maxRetries) {
                    log.error("Permanent error or max retries exceeded ({}/{}) calling Gemini Generation API: {}",
                            attempt, maxRetries + 1, ex.getMessage());
                    throw new GenerationServiceException("Gemini generation call failed: " + ex.getMessage(), ex);
                }

                long backoff = retryDelayMs * attempt;
                log.warn("Transient error on attempt {}/{}: {}. Retrying in {}ms...",
                        attempt, maxRetries + 1, ex.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GenerationServiceException("Generation retry interrupted", ie);
                }
            }
        }

        throw new GenerationServiceException("Failed to generate answer after " + (maxRetries + 1) + " attempts", lastException);
    }

    /**
     * Direct call to Gemini Generation API using the official Google Gen AI SDK.
     */
    private String callGeminiApiDirectly(String systemInstruction, String context, String question) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured. Please set GEMINI_API_KEY.");
        }

        Client client = Client.builder().apiKey(apiKey).build();

        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                .temperature(temperature)
                .maxOutputTokens(maxOutputTokens);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            configBuilder.systemInstruction(Content.fromParts(Part.fromText(systemInstruction)));
        }

        GenerateContentConfig config = configBuilder.build();

        // Separate retrieved context and user question distinctly
        String structuredPrompt = formatUserContent(context, question);

        GenerateContentResponse response = client.models.generateContent(model, structuredPrompt, config);
        if (response == null) {
            throw new GenerationServiceException("Gemini returned null GenerateContentResponse");
        }

        return response.text();
    }

    /**
     * Formats the prompt with distinct context and question sections.
     */
    private String formatUserContent(String context, String question) {
        return String.format(
                "CONTEXT:%n%s%n%nUSER QUESTION:%n%s",
                context,
                question
        );
    }

    /**
     * Determines whether an exception represents a transient failure eligible for retry.
     */
    private boolean isTransientError(Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        // Rate limit (429 / RESOURCE_EXHAUSTED / quota)
        if (msg.contains("429") || msg.contains("resource_exhausted") || msg.contains("quota")) {
            return true;
        }

        // Temporary server errors (500, 502, 503, 504, unavailable)
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

    @Override
    public String getModel() {
        return model;
    }

    public float getTemperature() {
        return temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }
}
