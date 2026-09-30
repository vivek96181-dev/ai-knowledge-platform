package com.enterprise.aiknowledge.observability;

import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class PlatformMetricsTest {

    private MeterRegistry meterRegistry;
    private DocumentRepository documentRepository;
    private ObjectProvider<DocumentRepository> documentRepositoryProvider;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        documentRepository = Mockito.mock(DocumentRepository.class);
        documentRepositoryProvider = Mockito.mock(ObjectProvider.class);
        when(documentRepositoryProvider.getIfAvailable()).thenReturn(documentRepository);

        platformMetrics = new PlatformMetrics(meterRegistry, documentRepositoryProvider, true);
        platformMetrics.registerGauges();
    }

    @Test
    @DisplayName("Should record search metrics accurately with mode and outcome tags")
    void testSearchMetrics() {
        platformMetrics.recordSearchRequest("SEMANTIC", "success");
        platformMetrics.recordSearchRequest("HYBRID", "success");
        platformMetrics.recordSearchLatency("SEMANTIC", "success", 120);
        platformMetrics.recordSearchResults("SEMANTIC", 10);
        platformMetrics.recordSearchFailure("KEYWORD", "timeout");

        assertThat(meterRegistry.get("aikp.search.requests").tag("mode", "semantic").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.search.requests").tag("mode", "hybrid").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.search.latency").tag("mode", "semantic").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(120.0);
        assertThat(meterRegistry.get("aikp.search.results").tag("mode", "semantic").counter().count())
                .isEqualTo(10.0);
        assertThat(meterRegistry.get("aikp.search.failures").tag("mode", "keyword").tag("reason", "timeout").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record rerank metrics accurately")
    void testRerankMetrics() {
        platformMetrics.recordRerankRequest("success");
        platformMetrics.recordRerankLatency("success", 350);
        platformMetrics.recordRerankCandidates(25);
        platformMetrics.setRerankingEnabled(true);
        platformMetrics.recordRerankFailure("model_unavailable");
        platformMetrics.recordRerankFallback();

        assertThat(meterRegistry.get("aikp.rerank.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.rerank.latency").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(350.0);
        assertThat(meterRegistry.get("aikp.rerank.candidates").summary().totalAmount())
                .isEqualTo(25.0);
        assertThat(meterRegistry.get("aikp.rerank.status").gauge().value())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.rerank.failures").tag("reason", "model_unavailable").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.rerank.fallbacks").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record RAG metrics including retrieval, generation, and total latency")
    void testRagMetrics() {
        platformMetrics.recordRagRequest("success");
        platformMetrics.recordRagRetrievalLatency("success", 150);
        platformMetrics.recordRagGenerationLatency("success", 850);
        platformMetrics.recordRagTotalLatency("success", 1000);
        platformMetrics.recordRagFailure("generation", "llm_rate_limit");

        assertThat(meterRegistry.get("aikp.rag.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.rag.retrieval.latency").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(150.0);
        assertThat(meterRegistry.get("aikp.rag.generation.latency").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(850.0);
        assertThat(meterRegistry.get("aikp.rag.total.latency").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1000.0);
        assertThat(meterRegistry.get("aikp.rag.failures").tag("stage", "generation").tag("reason", "llm_rate_limit").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record Redis cache hits, misses, failures, and invalidations")
    void testRedisMetrics() {
        platformMetrics.recordCacheHit("search");
        platformMetrics.recordCacheMiss("rag");
        platformMetrics.recordCacheReadFailure("search");
        platformMetrics.recordCacheWriteFailure("rag");
        platformMetrics.recordCacheInvalidation("search");

        assertThat(meterRegistry.get("aikp.cache.hits").tag("cache", "search").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.cache.misses").tag("cache", "rag").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.cache.read.failures").tag("cache", "search").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.cache.write.failures").tag("cache", "rag").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.cache.invalidations").tag("target", "search").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record document processing metrics, failures by phase, and status gauges")
    void testDocumentProcessingMetrics() {
        when(documentRepository.countByStatus(DocumentStatus.COMPLETED)).thenReturn(42L);
        when(documentRepository.countByStatus(DocumentStatus.FAILED)).thenReturn(3L);

        platformMetrics.recordDocumentUploaded(true);
        platformMetrics.recordDocumentProcessed(true);
        platformMetrics.recordDocumentProcessingLatency(true, 2400);
        platformMetrics.recordDocumentFailure("extraction");
        platformMetrics.recordDocumentFailure("embedding");

        assertThat(meterRegistry.get("aikp.documents.uploaded").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.documents.processed").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.documents.processing.latency").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(2400.0);
        assertThat(meterRegistry.get("aikp.documents.failed").tag("phase", "extraction").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.documents.failed").tag("phase", "embedding").counter().count())
                .isEqualTo(1.0);

        assertThat(meterRegistry.get("aikp.documents.by_status").tag("status", "COMPLETED").gauge().value())
                .isEqualTo(42.0);
        assertThat(meterRegistry.get("aikp.documents.by_status").tag("status", "FAILED").gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("Should record Qdrant search, upsert, latency, and error metrics")
    void testQdrantMetrics() {
        platformMetrics.recordQdrantSearch(true, 75);
        platformMetrics.recordQdrantUpsert(true, 180);
        platformMetrics.recordQdrantError("SEARCH");

        assertThat(meterRegistry.get("aikp.qdrant.search.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.qdrant.upsert.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.qdrant.latency").tag("operation", "search").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(75.0);
        assertThat(meterRegistry.get("aikp.qdrant.latency").tag("operation", "upsert").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(180.0);
        assertThat(meterRegistry.get("aikp.qdrant.errors").tag("operation", "search").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record Kafka event publication, consumption, and processing failures")
    void testKafkaMetrics() {
        platformMetrics.recordKafkaEventPublished("document-uploaded", true);
        platformMetrics.recordKafkaEventConsumed("document-uploaded", true);
        platformMetrics.recordKafkaProcessingFailure("deserialization_error");

        assertThat(meterRegistry.get("aikp.kafka.events.published").tag("topic", "document-uploaded").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.kafka.events.consumed").tag("topic", "document-uploaded").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("aikp.kafka.processing.failures").tag("reason", "deserialization_error").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should strictly enforce low-cardinality tags and prohibit sensitive tags")
    void testSafeTagCardinalityAndNoSensitiveTags() {
        // Trigger all metric recording methods
        platformMetrics.recordSearchRequest("SEMANTIC", "success");
        platformMetrics.recordSearchLatency("SEMANTIC", "success", 100);
        platformMetrics.recordSearchResults("SEMANTIC", 5);
        platformMetrics.recordSearchFailure("SEMANTIC", "timeout");
        platformMetrics.recordRerankRequest("success");
        platformMetrics.recordRerankLatency("success", 200);
        platformMetrics.recordRerankCandidates(10);
        platformMetrics.setRerankingEnabled(true);
        platformMetrics.recordRerankFailure("error");
        platformMetrics.recordRerankFallback();
        platformMetrics.recordRagRequest("success");
        platformMetrics.recordRagRetrievalLatency("success", 50);
        platformMetrics.recordRagGenerationLatency("success", 150);
        platformMetrics.recordRagTotalLatency("success", 200);
        platformMetrics.recordRagFailure("generation", "error");
        platformMetrics.recordCacheHit("search");
        platformMetrics.recordCacheMiss("rag");
        platformMetrics.recordCacheReadFailure("search");
        platformMetrics.recordCacheWriteFailure("rag");
        platformMetrics.recordCacheInvalidation("search");
        platformMetrics.recordDocumentUploaded(true);
        platformMetrics.recordDocumentProcessed(true);
        platformMetrics.recordDocumentProcessingLatency(true, 500);
        platformMetrics.recordDocumentFailure("chunking");
        platformMetrics.recordQdrantSearch(true, 50);
        platformMetrics.recordQdrantUpsert(true, 60);
        platformMetrics.recordQdrantError("UPSERT");
        platformMetrics.recordKafkaEventPublished("test-topic", true);
        platformMetrics.recordKafkaEventConsumed("test-topic", true);
        platformMetrics.recordKafkaProcessingFailure("error");

        Set<String> allowedTagKeys = Set.of(
                "application", "mode", "outcome", "status", "phase",
                "stage", "cache", "reason", "operation", "topic", "target"
        );

        Set<String> prohibitedPatterns = Set.of(
                "user", "userid", "user_id", "query", "prompt", "doc", "documentid",
                "document_id", "chunk", "chunkid", "chunk_id", "jwt", "bearer",
                "token", "apikey", "api_key", "secret", "password"
        );

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase();
                String value = tag.getValue().toLowerCase();

                assertThat(allowedTagKeys)
                        .as("Tag key '%s' on metric '%s' must be an authorized low-cardinality key", tag.getKey(), meter.getId().getName())
                        .contains(tag.getKey());

                for (String prohibited : prohibitedPatterns) {
                    assertThat(key)
                            .as("Tag key '%s' on metric '%s' must not contain sensitive identifier '%s'", tag.getKey(), meter.getId().getName(), prohibited)
                            .isNotEqualTo(prohibited);

                    assertThat(value)
                            .as("Tag value '%s' on metric '%s' must not contain sensitive identifier '%s'", tag.getValue(), meter.getId().getName(), prohibited)
                            .doesNotContain("bearer ")
                            .doesNotContain("eyJ"); // Typical JWT header base64 prefix
                }
            }
        }
    }
}
