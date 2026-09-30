package com.enterprise.aiknowledge.observability;

import com.enterprise.aiknowledge.service.QdrantVectorStoreService;
import com.enterprise.aiknowledge.service.VectorStoreService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Boot Actuator {@link HealthIndicator} verifying connectivity
 * and collection accessibility to the Qdrant Vector Database.
 */
@Component("qdrant")
public class QdrantHealthIndicator implements HealthIndicator {

    private final VectorStoreService vectorStoreService;

    public QdrantHealthIndicator(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @Override
    public Health health() {
        if (vectorStoreService == null) {
            return Health.down()
                    .withDetail("collection", "unknown")
                    .withDetail("reason", "QdrantVectorStoreService not available")
                    .build();
        }
        try {
            if (vectorStoreService instanceof QdrantVectorStoreService qdrantService) {
                if (qdrantService.isHealthy()) {
                    return Health.up()
                            .withDetail("collection", qdrantService.getCollectionName())
                            .withDetail("dimensions", qdrantService.getVectorDimensions())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("collection", qdrantService.getCollectionName())
                            .withDetail("error", "Qdrant collection inaccessible or server unreachable")
                            .build();
                }
            }
            return Health.up()
                    .withDetail("collection", vectorStoreService.getCollectionName())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("collection", vectorStoreService != null ? vectorStoreService.getCollectionName() : "unknown")
                    .build();
        }
    }
}
