package com.enterprise.aiknowledge.observability;

import com.enterprise.aiknowledge.service.QdrantVectorStoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QdrantHealthIndicatorTest {

    @Test
    @DisplayName("Should report UP when Qdrant service is reachable and collection exists")
    void testHealthUp() {
        QdrantVectorStoreService qdrantService = mock(QdrantVectorStoreService.class);
        when(qdrantService.isHealthy()).thenReturn(true);
        when(qdrantService.getCollectionName()).thenReturn("document_chunks");

        QdrantHealthIndicator indicator = new QdrantHealthIndicator(qdrantService);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("collection", "document_chunks");
    }

    @Test
    @DisplayName("Should report DOWN when Qdrant service is unhealthy")
    void testHealthDown() {
        QdrantVectorStoreService qdrantService = mock(QdrantVectorStoreService.class);
        when(qdrantService.isHealthy()).thenReturn(false);
        when(qdrantService.getCollectionName()).thenReturn("document_chunks");

        QdrantHealthIndicator indicator = new QdrantHealthIndicator(qdrantService);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Qdrant collection inaccessible or server unreachable");
    }

    @Test
    @DisplayName("Should report DOWN when Qdrant service bean is missing")
    void testHealthDownWhenNull() {
        QdrantHealthIndicator indicator = new QdrantHealthIndicator(null);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "QdrantVectorStoreService not available");
    }
}
