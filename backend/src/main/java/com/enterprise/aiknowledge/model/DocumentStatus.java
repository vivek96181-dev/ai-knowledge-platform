package com.enterprise.aiknowledge.model;

/**
 * Lifecycle status of a document in the system.
 *
 * <ul>
 *   <li>{@code UPLOADED}   — File uploaded to storage; metadata saved. Initial status for all uploads.</li>
 *   <li>{@code PROCESSING} — In-progress processing (reserved for future pipeline steps).</li>
 *   <li>{@code COMPLETED}  — Processing successfully finished.</li>
 *   <li>{@code FAILED}     — Processing encountered an unrecoverable error.</li>
 * </ul>
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED
}
