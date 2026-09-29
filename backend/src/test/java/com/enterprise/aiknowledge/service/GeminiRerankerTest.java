package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.RerankCandidate;
import com.enterprise.aiknowledge.dto.RerankedCandidate;
import com.enterprise.aiknowledge.exception.RerankerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeminiReranker Unit Tests")
class GeminiRerankerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Empty candidate list returns empty without calling model invoker")
    void emptyCandidateListReturnsEmpty() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper,
                prompt -> {
                    invoked.set(true);
                    return "[]";
                }
        );

        List<RerankedCandidate> results = reranker.rerank("test query", Collections.emptyList(), 5);

        assertThat(results).isEmpty();
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("Blank query throws IllegalArgumentException")
    void blankQueryThrowsException() {
        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> "[]"
        );

        assertThatThrownBy(() -> reranker.rerank("   ", List.of(candidate(1L, "text")), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query cannot be blank");
    }

    @Test
    @DisplayName("topK <= 0 returns empty list")
    void topKZeroOrNegativeReturnsEmpty() {
        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> "[]"
        );

        assertThat(reranker.rerank("query", List.of(candidate(1L, "text")), 0)).isEmpty();
        assertThat(reranker.rerank("query", List.of(candidate(1L, "text")), -1)).isEmpty();
    }

    @Test
    @DisplayName("Scores candidates and sorts by relevance score descending")
    void scoresAndSortsDescending() {
        List<RerankCandidate> candidates = List.of(
                candidate(101L, "Chunk 101 text"),
                candidate(102L, "Chunk 102 text"),
                candidate(103L, "Chunk 103 text")
        );

        // 102 has highest score (0.95), 101 has 0.80, 103 has 0.20
        String mockResponse = """
                [
                  {"id": 101, "score": 0.80},
                  {"id": 102, "score": 0.95},
                  {"id": 103, "score": 0.20}
                ]
                """;

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> mockResponse
        );

        List<RerankedCandidate> results = reranker.rerank("leave policy", candidates, 5);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).candidate().chunkId()).isEqualTo(102L);
        assertThat(results.get(0).rerankScore()).isEqualTo(0.95f);

        assertThat(results.get(1).candidate().chunkId()).isEqualTo(101L);
        assertThat(results.get(1).rerankScore()).isEqualTo(0.80f);

        assertThat(results.get(2).candidate().chunkId()).isEqualTo(103L);
        assertThat(results.get(2).rerankScore()).isEqualTo(0.20f);
    }

    @Test
    @DisplayName("Truncates final list to specified topK")
    void truncatesToTopK() {
        List<RerankCandidate> candidates = List.of(
                candidate(1L, "text 1"),
                candidate(2L, "text 2"),
                candidate(3L, "text 3"),
                candidate(4L, "text 4")
        );

        String mockResponse = """
                [
                  {"id": 1, "score": 0.9},
                  {"id": 2, "score": 0.8},
                  {"id": 3, "score": 0.7},
                  {"id": 4, "score": 0.6}
                ]
                """;

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> mockResponse
        );

        List<RerankedCandidate> results = reranker.rerank("query", candidates, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).candidate().chunkId()).isEqualTo(1L);
        assertThat(results.get(1).candidate().chunkId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Deterministic tie-breaking: equal scores are sorted by chunkId ascending")
    void deterministicTieBreakingByChunkIdAscending() {
        List<RerankCandidate> candidates = List.of(
                candidate(200L, "text 200"),
                candidate(100L, "text 100")
        );

        String mockResponse = """
                [
                  {"id": 200, "score": 0.85},
                  {"id": 100, "score": 0.85}
                ]
                """;

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> mockResponse
        );

        List<RerankedCandidate> results = reranker.rerank("query", candidates, 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).rerankScore()).isEqualTo(results.get(1).rerankScore());
        assertThat(results.get(0).candidate().chunkId()).isEqualTo(100L);
        assertThat(results.get(1).candidate().chunkId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("Handles markdown json code fences gracefully")
    void handlesMarkdownCodeFences() {
        List<RerankCandidate> candidates = List.of(candidate(10L, "text"));

        String mockResponse = "```json\n[{\"id\": 10, \"score\": 0.92}]\n```";

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> mockResponse
        );

        List<RerankedCandidate> results = reranker.rerank("query", candidates, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rerankScore()).isEqualTo(0.92f);
    }

    @Test
    @DisplayName("Malformed JSON throws RerankerException")
    void malformedJsonThrowsException() {
        List<RerankCandidate> candidates = List.of(candidate(10L, "text"));

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper, prompt -> "Not a valid JSON response"
        );

        assertThatThrownBy(() -> reranker.rerank("query", candidates, 5))
                .isInstanceOf(RerankerException.class)
                .hasMessageContaining("Malformed JSON");
    }

    @Test
    @DisplayName("Model timeout throws RerankerException")
    void timeoutThrowsException() {
        List<RerankCandidate> candidates = List.of(candidate(10L, "text"));

        // Simulates 1000ms delay with 100ms timeout
        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 100, objectMapper,
                prompt -> {
                    Thread.sleep(1000);
                    return "[]";
                }
        );

        assertThatThrownBy(() -> reranker.rerank("query", candidates, 5))
                .isInstanceOf(RerankerException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("Provider error propagates as RerankerException")
    void providerErrorPropagates() {
        List<RerankCandidate> candidates = List.of(candidate(10L, "text"));

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper,
                prompt -> {
                    throw new RuntimeException("API rate limit exceeded");
                }
        );

        assertThatThrownBy(() -> reranker.rerank("query", candidates, 5))
                .isInstanceOf(RerankerException.class)
                .hasMessageContaining("API rate limit exceeded");
    }

    @Test
    @DisplayName("Prompt builder excludes credentials, tokens, and database metadata")
    void promptExcludesSensitiveMetadata() {
        AtomicBoolean verified = new AtomicBoolean(false);

        List<RerankCandidate> candidates = List.of(
                new RerankCandidate(101L, 10L, 1, 0, 0.03f, "Clean safe text content")
        );

        GeminiReranker reranker = new GeminiReranker(
                true, "gemini-2.5-flash", "test-key", 5000, objectMapper,
                prompt -> {
                    assertThat(prompt).contains("Clean safe text content");
                    assertThat(prompt).contains("101");
                    assertThat(prompt).doesNotContain("password");
                    assertThat(prompt).doesNotContain("jwt");
                    assertThat(prompt).doesNotContain("Bearer");
                    assertThat(prompt).doesNotContain("jdbc:");
                    verified.set(true);
                    return "[{\"id\": 101, \"score\": 0.9}]";
                }
        );

        reranker.rerank("test query", candidates, 5);
        assertThat(verified.get()).isTrue();
    }

    private RerankCandidate candidate(Long chunkId, String text) {
        return new RerankCandidate(chunkId, 1L, 1, 0, 0.016f, text);
    }
}
