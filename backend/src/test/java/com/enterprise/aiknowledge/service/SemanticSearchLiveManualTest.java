package com.enterprise.aiknowledge.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developer-only manual live test verifying semantic search querying against a local Qdrant instance.
 *
 * <p><strong>Prerequisites:</strong>
 * Run Qdrant locally via Docker Compose before executing:
 * <pre>
 *   docker compose -f infrastructure/docker-compose.yml up -d qdrant
 *   .\mvnw.cmd test -Dtest=SemanticSearchLiveManualTest
 * </pre>
 * </p>
 */
@Disabled("Manual live developer test - requires running local Qdrant container (localhost:6334). Run explicitly.")
class SemanticSearchLiveManualTest {

    private static final String MANUAL_COLLECTION = "document_chunks_live_test";
    private static final int DIMENSIONS = 768;

    @Test
    @DisplayName("Manual live test: Upserts test vector and queries nearest neighbors in local Qdrant")
    void manualLiveSearchTest() {
        System.out.println("=== Semantic Search Live Manual Test ===");
        System.out.println("Target: localhost:6334 (gRPC)");
        System.out.println("Collection: " + MANUAL_COLLECTION);

        QdrantVectorStoreService vectorStore = new QdrantVectorStoreService(
                "localhost", 6333, 6334, MANUAL_COLLECTION, DIMENSIONS, false, "", null
        );

        vectorStore.ensureCollectionExists();

        // Upsert sample vectors
        List<Float> vector1 = Collections.nCopies(DIMENSIONS, 0.05f);
        ChunkVectorDto chunk1 = new ChunkVectorDto(88801L, 9901L, 1, 0, 10L, vector1);
        vectorStore.upsertChunkVectors(List.of(chunk1));

        // Query with matching vector
        long start = System.currentTimeMillis();
        List<ScoredChunkDto> matches = vectorStore.search(vector1, 5, 10L);
        long duration = System.currentTimeMillis() - start;

        System.out.println("Qdrant Search Latency: " + duration + " ms");
        System.out.println("Matches retrieved: " + matches.size());
        for (ScoredChunkDto m : matches) {
            System.out.printf("  Chunk ID: %d, Doc ID: %d, Score: %.4f%n", m.chunkId(), m.documentId(), m.score());
        }

        // Clean up
        vectorStore.deleteVectorsByDocumentId(9901L);
        System.out.println("Cleanup completed.");
        System.out.println("=========================================");

        assertThat(matches).isNotEmpty();
    }
}
