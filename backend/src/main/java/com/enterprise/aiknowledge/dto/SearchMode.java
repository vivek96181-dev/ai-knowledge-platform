package com.enterprise.aiknowledge.dto;

/**
 * Supported search retrieval modes.
 *
 * <ul>
 *   <li>{@link #SEMANTIC}: Dense vector similarity search using Qdrant.</li>
 *   <li>{@link #KEYWORD}: Lexical full-text search using PostgreSQL FTS.</li>
 *   <li>{@link #HYBRID}: Fused semantic vector and lexical keyword search using Reciprocal Rank Fusion (RRF).</li>
 * </ul>
 */
public enum SearchMode {
    SEMANTIC,
    KEYWORD,
    HYBRID
}
