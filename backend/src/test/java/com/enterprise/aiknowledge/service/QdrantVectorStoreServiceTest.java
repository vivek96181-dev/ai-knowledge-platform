package com.enterprise.aiknowledge.service;

import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying {@link QdrantVectorStoreService} configuration validation, dimension checking,
 * collection compatibility verification, deterministic point ID construction, and point upsert/delete operations.
 */
class QdrantVectorStoreServiceTest {

    private static final String TEST_COLLECTION = "document_chunks_test";
    private static final int TEST_DIMENSIONS = 768;

    private QdrantVectorStoreService.QdrantClientAdapter mockAdapter;
    private List<Float> valid768Vector;

    @BeforeEach
    void setUp() {
        mockAdapter = mock(QdrantVectorStoreService.QdrantClientAdapter.class);
        valid768Vector = Collections.nCopies(TEST_DIMENSIONS, 0.05f);
    }

    @Test
    @DisplayName("Configuration validation rejects invalid parameters")
    void configurationValidation() {
        assertThatThrownBy(() -> new QdrantVectorStoreService("localhost", 6333, 6334, "", 768, false, "", mockAdapter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionName cannot be null or blank");

        assertThatThrownBy(() -> new QdrantVectorStoreService("localhost", 6333, 6334, TEST_COLLECTION, 0, false, "", mockAdapter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectorDimensions must be greater than 0");
    }

    @Test
    @DisplayName("Vector dimension mismatch throws IllegalStateException during upsert")
    void dimensionMismatchThrowsException() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        List<Float> invalid512Vector = Collections.nCopies(512, 0.01f);
        ChunkVectorDto invalidChunk = new ChunkVectorDto(1L, 10L, 1, 0, 99L, invalid512Vector);

        assertThatThrownBy(() -> service.upsertChunkVectors(List.of(invalidChunk)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch")
                .hasMessageContaining("expected 768");

        verify(mockAdapter, never()).upsert(anyString(), anyList());
    }

    @Test
    @DisplayName("Successful point upsert creates deterministic point ID and metadata payload")
    void successfulPointUpsert() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        ChunkVectorDto chunk = new ChunkVectorDto(456L, 123L, 2, 5, 99L, valid768Vector);

        service.upsertChunkVectors(List.of(chunk));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointStruct>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockAdapter).upsert(eq(TEST_COLLECTION), captor.capture());

        List<PointStruct> points = captor.getValue();
        assertThat(points).hasSize(1);

        PointStruct point = points.get(0);
        // Deterministic point ID from chunk ID
        assertThat(point.getId().getNum()).isEqualTo(456L);

        // Payload metadata
        var payload = point.getPayloadMap();
        assertThat(payload.get("documentId").getIntegerValue()).isEqualTo(123L);
        assertThat(payload.get("documentChunkId").getIntegerValue()).isEqualTo(456L);
        assertThat(payload.get("pageNumber").getIntegerValue()).isEqualTo(2);
        assertThat(payload.get("chunkIndex").getIntegerValue()).isEqualTo(5);
        assertThat(payload.get("ownerId").getIntegerValue()).isEqualTo(99L);
    }

    @Test
    @DisplayName("Collection creation triggered when collection does not exist")
    void createsCollectionWhenMissing() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        when(mockAdapter.collectionExists(TEST_COLLECTION)).thenReturn(false);

        service.ensureCollectionExists();

        verify(mockAdapter).createCollection(TEST_COLLECTION, TEST_DIMENSIONS);
        verify(mockAdapter, never()).getCollectionInfo(any());
    }

    @Test
    @DisplayName("Compatible existing collection is accepted without error")
    void acceptsCompatibleExistingCollection() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        CollectionInfo compatibleInfo = CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder()
                        .setParams(CollectionParams.newBuilder()
                                .setVectorsConfig(VectorsConfig.newBuilder()
                                        .setParams(VectorParams.newBuilder()
                                                .setSize(TEST_DIMENSIONS)
                                                .setDistance(Distance.Cosine)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        when(mockAdapter.collectionExists(TEST_COLLECTION)).thenReturn(true);
        when(mockAdapter.getCollectionInfo(TEST_COLLECTION)).thenReturn(compatibleInfo);

        service.ensureCollectionExists();

        verify(mockAdapter, never()).createCollection(anyString(), anyInt());
    }

    @Test
    @DisplayName("Incompatible existing collection dimensions fail with clear IllegalStateException")
    void rejectsIncompatibleCollectionDimensions() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        CollectionInfo incompatibleInfo = CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder()
                        .setParams(CollectionParams.newBuilder()
                                .setVectorsConfig(VectorsConfig.newBuilder()
                                        .setParams(VectorParams.newBuilder()
                                                .setSize(512) // Incompatible 512 vs expected 768
                                                .setDistance(Distance.Cosine)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        when(mockAdapter.collectionExists(TEST_COLLECTION)).thenReturn(true);
        when(mockAdapter.getCollectionInfo(TEST_COLLECTION)).thenReturn(incompatibleInfo);

        assertThatThrownBy(service::ensureCollectionExists)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Incompatible Qdrant collection")
                .hasMessageContaining("expected vector size 768, but found 512");
    }

    @Test
    @DisplayName("Incompatible existing collection distance metric fails with clear IllegalStateException")
    void rejectsIncompatibleCollectionDistance() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        CollectionInfo incompatibleInfo = CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder()
                        .setParams(CollectionParams.newBuilder()
                                .setVectorsConfig(VectorsConfig.newBuilder()
                                        .setParams(VectorParams.newBuilder()
                                                .setSize(TEST_DIMENSIONS)
                                                .setDistance(Distance.Euclid) // Incompatible Euclid vs expected Cosine
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        when(mockAdapter.collectionExists(TEST_COLLECTION)).thenReturn(true);
        when(mockAdapter.getCollectionInfo(TEST_COLLECTION)).thenReturn(incompatibleInfo);

        assertThatThrownBy(service::ensureCollectionExists)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Incompatible Qdrant collection")
                .hasMessageContaining("expected distance 'Cosine'");
    }

    @Test
    @DisplayName("deleteVectorsByDocumentId executes delete with documentId filter")
    void deleteVectorsByDocumentIdExecutesFilter() throws Exception {
        QdrantVectorStoreService service = new QdrantVectorStoreService(
                "localhost", 6333, 6334, TEST_COLLECTION, TEST_DIMENSIONS, false, "", mockAdapter);

        service.deleteVectorsByDocumentId(999L);

        verify(mockAdapter).delete(eq(TEST_COLLECTION), any(Filter.class));
    }
}
