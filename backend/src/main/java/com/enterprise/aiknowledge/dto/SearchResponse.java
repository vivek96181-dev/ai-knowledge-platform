package com.enterprise.aiknowledge.dto;

import java.util.List;

/**
 * Structured search response returned by {@code POST /api/search}.
 *
 * @param query   Submitted natural-language query
 * @param results List of matching document chunks, preserving semantic similarity ranking
 */
public record SearchResponse(
        String query,
        List<SearchResult> results
) {}
