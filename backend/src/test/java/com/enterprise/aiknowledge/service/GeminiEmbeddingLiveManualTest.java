package com.enterprise.aiknowledge.service;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developer-only manual live test against Google's Gemini Embedding API.
 *
 * <p><strong>Execution Instructions:</strong>
 * To run this test manually, provide your own GEMINI_API_KEY environment variable:
 * <pre>
 *   $env:GEMINI_API_KEY="your-real-key"
 *   .\mvnw.cmd test -Dtest=GeminiEmbeddingLiveManualTest
 * </pre>
 * </p>
 *
 * <p><strong>Privacy Guarantee:</strong>
 * Never prints full vectors, complete document texts, or API keys.</p>
 */
@Disabled("Manual live developer test - requires GEMINI_API_KEY environment variable. Run explicitly when testing live API.")
class GeminiEmbeddingLiveManualTest {

    private static final String LIVE_MODEL = "gemini-embedding-2";
    private static final int EXPECTED_DIMENSIONS = 768;

    @Test
    @DisplayName("Manual live test: Generates embedding via real Gemini Embedding 2 API")
    void manualLiveGeminiEmbeddingTest() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("gemini.api-key");
        }

        assertThat(apiKey)
                .withFailMessage("GEMINI_API_KEY environment variable or system property must be set to run this live test")
                .isNotBlank();

        System.out.println("=== Gemini Embedding 2 Live Test ===");
        System.out.println("Target Model: " + LIVE_MODEL);
        System.out.println("Expected Dimensions: " + EXPECTED_DIMENSIONS);

        Client client = Client.builder().apiKey(apiKey).build();
        EmbedContentConfig config = EmbedContentConfig.builder()
                .outputDimensionality(EXPECTED_DIMENSIONS)
                .build();

        String testInput = "Enterprise AI Knowledge Platform embedding generation validation.";

        long startTime = System.currentTimeMillis();
        EmbedContentResponse response = client.models.embedContent(LIVE_MODEL, testInput, config);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(response).isNotNull();
        assertThat(response.embeddings()).isPresent();
        assertThat(response.embeddings().get()).isNotEmpty();

        ContentEmbedding embedding = response.embeddings().get().get(0);
        assertThat(embedding.values()).isPresent();

        List<Float> vector = embedding.values().get();
        int actualDimensions = vector.size();

        System.out.println("API Call Status: SUCCESS");
        System.out.println("Returned Dimensions: " + actualDimensions);
        System.out.println("Latency: " + duration + " ms");
        System.out.println("=====================================");

        assertThat(actualDimensions).isEqualTo(EXPECTED_DIMENSIONS);
    }
}
