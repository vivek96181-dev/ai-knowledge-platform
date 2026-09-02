package com.enterprise.aiknowledge.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developer-only manual live test against a local Qdrant vector database instance.
 *
 * <p><strong>Prerequisites:</strong>
 * Run Qdrant locally via Docker Compose before running this test:
 * <pre>
 *   docker compose -f infrastructure/docker-compose.yml up -d qdrant
 *   .\mvnw.cmd test -Dtest=QdrantVectorStoreLiveManualTest
 * </pre>
 * </p>
 */
@Disabled("Manual live developer test - requires running local Qdrant container (localhost:6334). Run explicitly.")
class QdrantVectorStoreLiveManualTest {

    private static final String MANUAL_COLLECTION = "document_chunks_live_test";
    private static final int DIMENSIONS = 768;

    @Test
    @DisplayName("Manual live test: Creates collection, upserts points, and deletes by documentId in local Qdrant")
    void manualLiveQdrantTest() {
        System.out.println("=== Qdrant Vector Store Live Manual Test ===");
        System.out.println("Target: localhost:6334 (gRPC)");
        System.out.println("Collection: " + MANUAL_COLLECTION);
        System.out.println("Dimensions: " + DIMENSIONS);

        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, MANUAL_COLLECTION, DIMENSIONS, false, "", null
        );

        // 1. Ensure collection exists
        service.ensureCollectionExists();
        System.out.println("Collection check/create: SUCCESS");

        // 2. Upsert test point
        List<Float> dummyVector = Collections.nCopies(DIMENSIONS, 0.025f);
        ChunkVectorDto testChunk = new ChunkVectorDto(12345L, 9999L, 1, 0, 1L, dummyVector);

        long start = System.currentTimeMillis();
        service.upsertChunkVectors(List.of(testChunk));
        long duration = System.currentTimeMillis() - start;
        System.out.println("Point Upsert: SUCCESS (Latency: " + duration + " ms)");

        // 3. Delete points for test document
        service.deleteVectorsByDocumentId(9999L);
        System.out.println("Document Points Deletion: SUCCESS");
        System.out.println("============================================");

        assertThat(service.getVectorDimensions()).isEqualTo(DIMENSIONS);
    }
}
