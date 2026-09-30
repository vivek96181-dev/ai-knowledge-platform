package com.enterprise.aiknowledge.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaHealthIndicatorTest {

    @Test
    @DisplayName("Should report UNKNOWN when KafkaAdmin is null")
    void testKafkaUnknownWhenNull() {
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(null);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("reason", "KafkaAdmin not configured");
    }

    @Test
    @DisplayName("Should report DOWN gracefully when Kafka broker is unreachable")
    void testKafkaDownWhenUnreachable() {
        KafkaAdmin admin = new KafkaAdmin(Map.of(
                "bootstrap.servers", "localhost:1",
                "request.timeout.ms", "500",
                "default.api.timeout.ms", "500"
        ));
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(admin);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }
}
