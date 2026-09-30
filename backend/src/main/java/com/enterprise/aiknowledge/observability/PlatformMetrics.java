package com.enterprise.aiknowledge.observability;

import com.enterprise.aiknowledge.model.DocumentStatus;
import com.enterprise.aiknowledge.repository.DocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized Micrometer metrics registry and recorder for the Enterprise AI Knowledge Platform.
 *
 * <p><strong>Key Design Principles:</strong>
 * <ul>
 *   <li>Uses low-cardinality tags only (mode, outcome, operation, status, phase).</li>
 *   <li>NEVER accepts or exposes sensitive data (queries, document IDs, chunk IDs, user IDs, keys, JWTs).</li>
 *   <li>Leverages Micrometer {@link Timer}, {@link Counter}, {@link DistributionSummary}, and {@link Gauge}.</li>
 *   <li>Thread-safe metric recording for high-throughput enterprise pipelines.</li>
 * </ul>
 * </p>
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;
    private final ObjectProvider<DocumentRepository> documentRepositoryProvider;
    private final AtomicInteger rerankingStatusGauge = new AtomicInteger(1);

    public PlatformMetrics(
            MeterRegistry registry,
            ObjectProvider<DocumentRepository> documentRepositoryProvider,
            @Value("${search.reranking.enabled:true}") boolean rerankingEnabled) {
        this.registry = registry;
        this.documentRepositoryProvider = documentRepositoryProvider;
        this.rerankingStatusGauge.set(rerankingEnabled ? 1 : 0);
    }

    @PostConstruct
    public void registerGauges() {
        // Register reranking enabled/disabled gauge (1.0 = enabled, 0.0 = disabled)
        Gauge.builder("aikp.rerank.status", rerankingStatusGauge, AtomicInteger::get)
                .description("Reranking service operational status (1 = enabled, 0 = disabled)")
                .register(registry);

        // Register document counts by status gauge
        for (DocumentStatus status : DocumentStatus.values()) {
            Gauge.builder("aikp.documents.by_status", () -> {
                DocumentRepository repo = documentRepositoryProvider.getIfAvailable();
                if (repo != null) {
                    try {
                        return repo.countByStatus(status);
                    } catch (Exception e) {
                        return 0.0;
                    }
                }
                return 0.0;
            })
            .tag("status", status.name())
            .description("Total document count in PostgreSQL partitioned by processing status")
            .register(registry);
        }
    }

    // =========================================================================
    // 1. Search Metrics
    // =========================================================================

    public void recordSearchRequest(String mode, String outcome) {
        Counter.builder("aikp.search.requests")
                .tag("mode", sanitizeTag(mode))
                .tag("outcome", sanitizeTag(outcome))
                .description("Total search requests processed")
                .register(registry)
                .increment();
    }

    public void recordSearchLatency(String mode, String outcome, long durationMs) {
        Timer.builder("aikp.search.latency")
                .tag("mode", sanitizeTag(mode))
                .tag("outcome", sanitizeTag(outcome))
                .description("Search execution latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordSearchResults(String mode, int count) {
        Counter.builder("aikp.search.results")
                .tag("mode", sanitizeTag(mode))
                .description("Total number of search result items returned")
                .register(registry)
                .increment(count);
    }

    public void recordSearchFailure(String mode, String reason) {
        Counter.builder("aikp.search.failures")
                .tag("mode", sanitizeTag(mode))
                .tag("reason", sanitizeTag(reason))
                .description("Total search execution failures")
                .register(registry)
                .increment();
    }

    // =========================================================================
    // 2. Reranking Metrics
    // =========================================================================

    public void recordRerankRequest(String outcome) {
        Counter.builder("aikp.rerank.requests")
                .tag("outcome", sanitizeTag(outcome))
                .description("Total reranking requests")
                .register(registry)
                .increment();
    }

    public void recordRerankLatency(String outcome, long durationMs) {
        Timer.builder("aikp.rerank.latency")
                .tag("outcome", sanitizeTag(outcome))
                .description("Reranking model latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRerankFailure(String reason) {
        Counter.builder("aikp.rerank.failures")
                .tag("reason", sanitizeTag(reason))
                .description("Total reranking failures")
                .register(registry)
                .increment();
    }

    public void recordRerankFallback() {
        Counter.builder("aikp.rerank.fallbacks")
                .description("Total reranker failures that fell back to pre-reranked RRF scores")
                .register(registry)
                .increment();
    }

    public void recordRerankCandidates(int count) {
        DistributionSummary.builder("aikp.rerank.candidates")
                .description("Number of candidate chunks submitted to reranker")
                .register(registry)
                .record(count);
    }

    public void setRerankingEnabled(boolean enabled) {
        rerankingStatusGauge.set(enabled ? 1 : 0);
    }

    // =========================================================================
    // 3. RAG Metrics
    // =========================================================================

    public void recordRagRequest(String outcome) {
        Counter.builder("aikp.rag.requests")
                .tag("outcome", sanitizeTag(outcome))
                .description("Total RAG question-answering requests")
                .register(registry)
                .increment();
    }

    public void recordRagRetrievalLatency(String outcome, long durationMs) {
        Timer.builder("aikp.rag.retrieval.latency")
                .tag("outcome", sanitizeTag(outcome))
                .description("RAG retrieval phase latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRagGenerationLatency(String outcome, long durationMs) {
        Timer.builder("aikp.rag.generation.latency")
                .tag("outcome", sanitizeTag(outcome))
                .description("RAG LLM generation phase latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRagTotalLatency(String outcome, long durationMs) {
        Timer.builder("aikp.rag.total.latency")
                .tag("outcome", sanitizeTag(outcome))
                .description("Total end-to-end RAG request latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRagFailure(String stage, String reason) {
        Counter.builder("aikp.rag.failures")
                .tag("stage", sanitizeTag(stage))
                .tag("reason", sanitizeTag(reason))
                .description("Total RAG failures partitioned by execution stage")
                .register(registry)
                .increment();
    }

    // =========================================================================
    // 4. Redis / Cache Metrics
    // =========================================================================

    public void recordCacheHit(String cacheName) {
        Counter.builder("aikp.cache.hits")
                .tag("cache", sanitizeTag(cacheName))
                .description("Total cache lookup hits")
                .register(registry)
                .increment();
    }

    public void recordCacheMiss(String cacheName) {
        Counter.builder("aikp.cache.misses")
                .tag("cache", sanitizeTag(cacheName))
                .description("Total cache lookup misses")
                .register(registry)
                .increment();
    }

    public void recordCacheReadFailure(String cacheName) {
        Counter.builder("aikp.cache.read.failures")
                .tag("cache", sanitizeTag(cacheName))
                .description("Total Redis cache read exceptions")
                .register(registry)
                .increment();
    }

    public void recordCacheWriteFailure(String cacheName) {
        Counter.builder("aikp.cache.write.failures")
                .tag("cache", sanitizeTag(cacheName))
                .description("Total Redis cache write exceptions")
                .register(registry)
                .increment();
    }

    public void recordCacheInvalidation(String target) {
        Counter.builder("aikp.cache.invalidations")
                .tag("target", sanitizeTag(target))
                .description("Total Redis cache eviction/invalidation events")
                .register(registry)
                .increment();
    }

    // =========================================================================
    // 5. Document Processing Metrics
    // =========================================================================

    public void recordDocumentUploaded(boolean success) {
        Counter.builder("aikp.documents.uploaded")
                .tag("status", success ? "success" : "failure")
                .description("Total document upload requests")
                .register(registry)
                .increment();
    }

    public void recordDocumentProcessed(boolean success) {
        Counter.builder("aikp.documents.processed")
                .tag("outcome", success ? "success" : "failure")
                .description("Total document Kafka processing runs completed")
                .register(registry)
                .increment();
    }

    public void recordDocumentProcessingLatency(boolean success, long durationMs) {
        Timer.builder("aikp.documents.processing.latency")
                .tag("outcome", success ? "success" : "failure")
                .description("Document processing duration from Kafka receipt to completion")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordDocumentFailure(String phase) {
        Counter.builder("aikp.documents.failed")
                .tag("phase", sanitizeTag(phase))
                .description("Total document processing failures partitioned by pipeline phase")
                .register(registry)
                .increment();
    }

    // =========================================================================
    // 6. Qdrant Metrics
    // =========================================================================

    public void recordQdrantSearch(boolean success, long durationMs) {
        Counter.builder("aikp.qdrant.search.requests")
                .tag("outcome", success ? "success" : "failure")
                .description("Total Qdrant nearest-neighbor vector searches")
                .register(registry)
                .increment();

        Timer.builder("aikp.qdrant.latency")
                .tag("operation", "search")
                .description("Qdrant vector operation latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordQdrantUpsert(boolean success, long durationMs) {
        Counter.builder("aikp.qdrant.upsert.requests")
                .tag("outcome", success ? "success" : "failure")
                .description("Total Qdrant vector point upsert operations")
                .register(registry)
                .increment();

        Timer.builder("aikp.qdrant.latency")
                .tag("operation", "upsert")
                .description("Qdrant vector operation latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordQdrantDelete(boolean success, long durationMs) {
        Timer.builder("aikp.qdrant.latency")
                .tag("operation", "delete")
                .description("Qdrant vector operation latency")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordQdrantError(String operation) {
        Counter.builder("aikp.qdrant.errors")
                .tag("operation", sanitizeTag(operation))
                .description("Total Qdrant gRPC operation errors")
                .register(registry)
                .increment();
    }

    // =========================================================================
    // 7. Kafka Metrics
    // =========================================================================

    public void recordKafkaEventPublished(String topic, boolean success) {
        Counter.builder("aikp.kafka.events.published")
                .tag("topic", sanitizeTag(topic))
                .tag("status", success ? "success" : "failure")
                .description("Total Kafka events published")
                .register(registry)
                .increment();
    }

    public void recordKafkaEventConsumed(String topic, boolean success) {
        Counter.builder("aikp.kafka.events.consumed")
                .tag("topic", sanitizeTag(topic))
                .tag("status", success ? "success" : "failure")
                .description("Total Kafka events consumed")
                .register(registry)
                .increment();
    }

    public void recordKafkaProcessingFailure(String reason) {
        Counter.builder("aikp.kafka.processing.failures")
                .tag("reason", sanitizeTag(reason))
                .description("Total Kafka consumer processing failures")
                .register(registry)
                .increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    private String sanitizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase();
    }
}
