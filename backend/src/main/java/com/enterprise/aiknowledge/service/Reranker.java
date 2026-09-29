package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.RerankCandidate;
import com.enterprise.aiknowledge.dto.RerankedCandidate;

import java.util.List;

/**
 * Service interface for query-to-chunk relevance reranking.
 *
 * <p>Takes a bounded candidate list from Hybrid Search and re-scores candidates
 * based on fine-grained relevance to the user query.</p>
 */
public interface Reranker {

    /**
     * Scores candidates against the query, sorts by relevance score descending with deterministic
     * tie-breaking, and truncates to topK.
     *
     * @param query      user natural-language query
     * @param candidates authorized candidate chunks from hybrid retrieval
     * @param topK       maximum number of reranked candidates to return
     * @return reranked candidates ordered by relevance score descending
     */
    List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates, int topK);

    /**
     * Whether reranking is globally enabled by configuration.
     */
    boolean isEnabled();

    /**
     * Name or identifier of the underlying reranker model.
     */
    String getModelName();
}
