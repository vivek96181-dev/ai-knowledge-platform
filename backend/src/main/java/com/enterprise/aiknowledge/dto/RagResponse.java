package com.enterprise.aiknowledge.dto;

import java.util.List;

/**
 * Structured response payload for RAG question-answering (POST /api/rag/ask).
 *
 * @param query   The original natural-language query submitted by the user
 * @param answer  The grounded answer generated strictly from retrieved context
 * @param sources List of authorized document chunks included in the generation context
 */
public record RagResponse(
        String query,
        String answer,
        List<RagSource> sources
) {}
