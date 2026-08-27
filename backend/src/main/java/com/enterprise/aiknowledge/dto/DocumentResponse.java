package com.enterprise.aiknowledge.dto;

import com.enterprise.aiknowledge.model.DocumentStatus;

import java.time.LocalDateTime;

/**
 * Response DTO returned by Document API endpoints.
 *
 * <p><strong>Security Note:</strong> Internal physical storage details
 * (such as {@code storagePath} and {@code storedFilename}) are intentionally omitted
 * to prevent leaking server filesystem details.</p>
 *
 * @param id               Unique database identifier
 * @param originalFilename Name of the file uploaded by the user
 * @param contentType      MIME type (e.g., "application/pdf")
 * @param fileSize         Size of the file in bytes
 * @param status           Current document processing status
 * @param createdAt        Upload timestamp
 * @param updatedAt        Last modification timestamp
 * @param ownerId          User ID of the document owner
 * @param ownerEmail       Email address of the document owner
 */
public record DocumentResponse(
        Long id,
        String originalFilename,
        String contentType,
        Long fileSize,
        DocumentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long ownerId,
        String ownerEmail
) {}
