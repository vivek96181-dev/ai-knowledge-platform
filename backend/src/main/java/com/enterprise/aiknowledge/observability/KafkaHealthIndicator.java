package com.enterprise.aiknowledge.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Health indicator for Apache Kafka broker connectivity.
 * Uses a lightweight cluster description check with bounded timeout.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealthIndicator.class);
    private final KafkaAdmin kafkaAdmin;

    @Autowired
    public KafkaHealthIndicator(@Autowired(required = false) KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        if (kafkaAdmin == null) {
            return Health.unknown().withDetail("reason", "KafkaAdmin not configured").build();
        }

        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult cluster = adminClient.describeCluster();
            String clusterId = cluster.clusterId().get(2, TimeUnit.SECONDS);
            int nodeCount = cluster.nodes().get(2, TimeUnit.SECONDS).size();

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodes", nodeCount)
                    .build();
        } catch (Exception e) {
            log.debug("Kafka health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("reason", "Kafka cluster unreachable")
                    .withDetail("error", e.getMessage() != null ? e.getMessage() : "Unknown error")
                    .build();
        }
    }
}
