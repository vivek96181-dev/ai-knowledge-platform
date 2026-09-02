package com.enterprise.aiknowledge.service;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Production implementation of {@link VectorStoreService} using Qdrant Vector Database via gRPC.
 *
 * <p><strong>Key Design Principles:</strong>
 * <ul>
 *   <li>Configurable collection name and vector dimensions (default 768, Cosine distance)</li>
 *   <li>Deterministic point IDs derived from DocumentChunk ID (collision-free & idempotent)</li>
 *   <li>Payload indexing for fast metadata filtering (documentId, documentChunkId, pageNumber, chunkIndex, ownerId)</li>
 *   <li>Zero raw text stored in Qdrant; PostgreSQL remains the single source of truth</li>
 *   <li>Idempotent collection initialization with strict dimension and distance verification</li>
 * </ul>
 * </p>
 */
@Service
public class QdrantVectorStoreService implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreService.class);

    private final String host;
    private final int port;
    private final int grpcPort;
    private final String collectionName;
    private final int vectorDimensions;
    private final boolean useTls;
    private final String apiKey;

    private final QdrantClientAdapter clientAdapter;

    /**
     * Interface isolating low-level Qdrant gRPC calls for seamless offline unit testing.
     */
    public interface QdrantClientAdapter {
        boolean collectionExists(String collectionName) throws Exception;
        void createCollection(String collectionName, int dimensions) throws Exception;
        CollectionInfo getCollectionInfo(String collectionName) throws Exception;
        void upsert(String collectionName, List<PointStruct> points) throws Exception;
        void delete(String collectionName, Filter filter) throws Exception;
        List<io.qdrant.client.grpc.Points.ScoredPoint> search(io.qdrant.client.grpc.Points.SearchPoints searchPoints) throws Exception;
    }

    private static class DefaultQdrantClientAdapter implements QdrantClientAdapter {
        private final QdrantClient client;

        DefaultQdrantClientAdapter(QdrantClient client) {
            this.client = client;
        }

        @Override
        public boolean collectionExists(String collectionName) throws Exception {
            return client.collectionExistsAsync(collectionName).get();
        }

        @Override
        public void createCollection(String collectionName, int dimensions) throws Exception {
            CreateCollection createRequest = CreateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setVectorsConfig(
                            VectorsConfig.newBuilder()
                                    .setParams(
                                            VectorParams.newBuilder()
                                                    .setSize(dimensions)
                                                    .setDistance(Distance.Cosine)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();
            client.createCollectionAsync(createRequest).get();
        }

        @Override
        public CollectionInfo getCollectionInfo(String collectionName) throws Exception {
            return client.getCollectionInfoAsync(collectionName).get();
        }

        @Override
        public void upsert(String collectionName, List<PointStruct> points) throws Exception {
            client.upsertAsync(collectionName, points).get();
        }

        @Override
        public void delete(String collectionName, Filter filter) throws Exception {
            client.deleteAsync(collectionName, filter).get();
        }

        @Override
        public List<io.qdrant.client.grpc.Points.ScoredPoint> search(io.qdrant.client.grpc.Points.SearchPoints searchPoints) throws Exception {
            return client.searchAsync(searchPoints).get();
        }
    }

    @Autowired
    public QdrantVectorStoreService(
            @Value("${qdrant.host:localhost}") String host,
            @Value("${qdrant.port:6333}") int port,
            @Value("${qdrant.grpc-port:6334}") int grpcPort,
            @Value("${qdrant.collection-name:document_chunks}") String collectionName,
            @Value("${qdrant.vector-dimensions:768}") int vectorDimensions,
            @Value("${qdrant.use-tls:false}") boolean useTls,
            @Value("${qdrant.api-key:}") String apiKey) {
        this(host, port, grpcPort, collectionName, vectorDimensions, useTls, apiKey, null);
    }

    public QdrantVectorStoreService(
            String host,
            int port,
            int grpcPort,
            String collectionName,
            int vectorDimensions,
            boolean useTls,
            String apiKey,
            QdrantClientAdapter customAdapter) {
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be greater than 0, but was: " + vectorDimensions);
        }
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName cannot be null or blank");
        }

        this.host = host;
        this.port = port;
        this.grpcPort = grpcPort;
        this.collectionName = collectionName;
        this.vectorDimensions = vectorDimensions;
        this.useTls = useTls;
        this.apiKey = apiKey;

        if (customAdapter != null) {
            this.clientAdapter = customAdapter;
        } else {
            this.clientAdapter = new DefaultQdrantClientAdapter(buildDefaultClient());
        }
    }

    private QdrantClient buildDefaultClient() {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, grpcPort, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    @PostConstruct
    public void init() {
        try {
            ensureCollectionExists();
        } catch (Exception ex) {
            log.warn("Could not initialize Qdrant collection on startup (host: {}:{}): {}. Will retry on demand.",
                    host, grpcPort, ex.getMessage());
        }
    }

    @Override
    public void ensureCollectionExists() {
        log.info("Verifying Qdrant collection '{}' (dimensions: {}, distance: Cosine)...",
                collectionName, vectorDimensions);

        try {
            boolean exists = clientAdapter.collectionExists(collectionName);
            if (!exists) {
                log.info("Collection '{}' does not exist in Qdrant. Creating...", collectionName);
                clientAdapter.createCollection(collectionName, vectorDimensions);
                log.info("Successfully created Qdrant collection '{}'", collectionName);
            } else {
                log.info("Collection '{}' exists in Qdrant. Validating compatibility...", collectionName);
                CollectionInfo info = clientAdapter.getCollectionInfo(collectionName);
                validateCollectionCompatibility(info);
                log.info("Collection '{}' is compatible and ready", collectionName);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while verifying Qdrant collection: " + collectionName, ie);
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException("Failed to verify/create Qdrant collection '" + collectionName + "': " + cause.getMessage(), cause);
        }
    }

    private void validateCollectionCompatibility(CollectionInfo info) {
        if (info == null || !info.hasConfig() || !info.getConfig().hasParams()) {
            return;
        }

        var vectorsConfig = info.getConfig().getParams().getVectorsConfig();
        if (vectorsConfig.hasParams()) {
            VectorParams params = vectorsConfig.getParams();
            if (params.getSize() != vectorDimensions) {
                throw new IllegalStateException(String.format(
                        "Incompatible Qdrant collection '%s': expected vector size %d, but found %d",
                        collectionName, vectorDimensions, params.getSize()));
            }
            if (params.getDistance() != Distance.Cosine) {
                throw new IllegalStateException(String.format(
                        "Incompatible Qdrant collection '%s': expected distance 'Cosine', but found '%s'",
                        collectionName, params.getDistance()));
            }
        }
    }

    @Override
    public void upsertChunkVectors(List<ChunkVectorDto> chunkVectors) {
        if (chunkVectors == null || chunkVectors.isEmpty()) {
            return;
        }

        log.info("Upserting {} chunk vectors into Qdrant collection '{}'...", chunkVectors.size(), collectionName);
        List<PointStruct> points = new ArrayList<>(chunkVectors.size());

        for (ChunkVectorDto item : chunkVectors) {
            validateVectorDimensions(item.chunkId(), item.vector());

            // Build payload metadata map (zero raw text to keep PostgreSQL as single source of truth)
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new LinkedHashMap<>();
            payload.put("documentId", ValueFactory.value(item.documentId()));
            payload.put("documentChunkId", ValueFactory.value(item.chunkId()));
            payload.put("pageNumber", ValueFactory.value(item.pageNumber()));
            payload.put("chunkIndex", ValueFactory.value(item.chunkIndex()));
            if (item.ownerId() != null) {
                payload.put("ownerId", ValueFactory.value(item.ownerId()));
            }

            PointStruct point = PointStruct.newBuilder()
                    .setId(PointIdFactory.id(item.chunkId()))
                    .setVectors(VectorsFactory.vectors(item.vector()))
                    .putAllPayload(payload)
                    .build();

            points.add(point);
        }

        try {
            clientAdapter.upsert(collectionName, points);
            log.info("Successfully upserted {} points into Qdrant collection '{}'", points.size(), collectionName);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during Qdrant upsert for collection: " + collectionName, ie);
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException("Qdrant upsert failed for collection '" + collectionName + "': " + cause.getMessage(), cause);
        }
    }

    @Override
    public void deleteVectorsByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }

        log.info("Deleting Qdrant points in collection '{}' for documentId: {}...", collectionName, documentId);
        try {
            Filter filter = Filter.newBuilder()
                    .addMust(ConditionFactory.match("documentId", documentId))
                    .build();

            clientAdapter.delete(collectionName, filter);
            log.info("Successfully deleted Qdrant points for documentId: {}", documentId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during Qdrant delete for documentId: " + documentId, ie);
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException("Qdrant delete failed for documentId " + documentId + ": " + cause.getMessage(), cause);
        }
    }

    @Override
    public List<ScoredChunkDto> search(List<Float> queryVector, int topK, Long ownerId) {
        if (queryVector == null || queryVector.size() != vectorDimensions) {
            throw new IllegalStateException(String.format(
                    "Query vector dimension mismatch: expected %d, but was %d",
                    vectorDimensions, queryVector != null ? queryVector.size() : 0));
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than 0, but was: " + topK);
        }

        log.info("Searching Qdrant collection '{}' for top-{} nearest neighbors (ownerId filter: {})...",
                collectionName, topK, ownerId);

        SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(queryVector)
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setWithVectors(WithVectorsSelectorFactory.enable(false));

        if (ownerId != null) {
            Filter filter = Filter.newBuilder()
                    .addMust(ConditionFactory.match("ownerId", ownerId))
                    .build();
            searchBuilder.setFilter(filter);
        }

        try {
            List<ScoredPoint> scoredPoints = clientAdapter.search(searchBuilder.build());
            log.info("Qdrant returned {} matching points", scoredPoints.size());

            List<ScoredChunkDto> results = new ArrayList<>(scoredPoints.size());
            for (ScoredPoint point : scoredPoints) {
                Long chunkId = point.getId().getNum();
                float score = point.getScore();

                var payload = point.getPayloadMap();
                Long docId = payload.containsKey("documentId") ? payload.get("documentId").getIntegerValue() : null;
                int pageNumber = payload.containsKey("pageNumber") ? (int) payload.get("pageNumber").getIntegerValue() : 0;
                int chunkIndex = payload.containsKey("chunkIndex") ? (int) payload.get("chunkIndex").getIntegerValue() : 0;
                Long pointOwnerId = payload.containsKey("ownerId") ? payload.get("ownerId").getIntegerValue() : null;

                results.add(new ScoredChunkDto(chunkId, docId, pageNumber, chunkIndex, pointOwnerId, score));
            }
            return results;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during Qdrant vector search in collection: " + collectionName, ie);
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException("Qdrant vector search failed for collection '" + collectionName + "': " + cause.getMessage(), cause);
        }
    }

    private void validateVectorDimensions(Long chunkId, List<Float> vector) {
        if (vector.size() != vectorDimensions) {
            throw new IllegalStateException(String.format(
                    "Vector dimension mismatch for chunkId %d: expected %d, but was %d",
                    chunkId, vectorDimensions, vector.size()));
        }
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public int getVectorDimensions() {
        return vectorDimensions;
    }
}
