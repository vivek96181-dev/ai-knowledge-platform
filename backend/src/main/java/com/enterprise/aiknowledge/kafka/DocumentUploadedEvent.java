package com.enterprise.aiknowledge.kafka;

/**
 * Event published to Kafka when a document is uploaded.
 *
 * <p><strong>Architectural Note — Why PDF Bytes are NOT stored in Kafka:</strong><br>
 * Storing raw PDF binary data in Kafka messages would cause topic log bloat, high broker memory usage,
 * replication slowdowns, and GC pauses on workers. Kafka is designed for lightweight event references.
 * The physical file is stored in storage (disk/S3), and this event carries only metadata references.</p>
 *
 * @param documentId       Database primary key of the uploaded document
 * @param ownerId          User ID of the document owner
 * @param storagePath      Storage path to the physical file
 * @param originalFilename Original filename provided by the uploader
 */
public record DocumentUploadedEvent(
        Long documentId,
        Long ownerId,
        String storagePath,
        String originalFilename
) {}
