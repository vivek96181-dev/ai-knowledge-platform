package com.enterprise.aiknowledge.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Dedicated producer component for publishing document events to Kafka.
 */
@Component
public class DocumentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventProducer.class);

    private final KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate;
    private final String topicName;

    public DocumentEventProducer(
            KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate,
            @Value("${kafka.topic.document-uploaded:document-uploaded}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    /**
     * Publishes a {@link DocumentUploadedEvent} to the configured Kafka topic.
     *
     * @param event event payload containing document metadata reference
     */
    public void sendDocumentUploadedEvent(DocumentUploadedEvent event) {
        String key = event.documentId() != null ? event.documentId().toString() : null;
        log.info("Publishing DocumentUploadedEvent to topic '{}' for document ID: {}", topicName, event.documentId());

        try {
            kafkaTemplate.send(topicName, key, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver DocumentUploadedEvent for document ID: {} to topic: {}",
                            event.documentId(), topicName, ex);
                } else {
                    log.info("Successfully delivered DocumentUploadedEvent for document ID: {} to partition {} offset {}",
                            event.documentId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception ex) {
            log.error("Kafka publishing exception for document ID: {}", event.documentId(), ex);
        }
    }
}
