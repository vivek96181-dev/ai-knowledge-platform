package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.List;

/**
 * Combined evaluation outcome for a single test case.
 *
 * @param caseId              identifier of the evaluated test case
 * @param question            evaluated question
 * @param retrieval           calculated retrieval metrics
 * @param generation          calculated generation metrics
 * @param retrievedChunkIds   ordered list of retrieved chunk IDs
 * @param generatedAnswer     answer string produced by RAG
 * @param passedSecurityCheck whether multi-tenant isolation rules were strictly satisfied
 */
public record CaseEvaluationResult(
        String caseId,
        String question,
        RetrievalMetrics retrieval,
        GenerationMetrics generation,
        List<Long> retrievedChunkIds,
        String generatedAnswer,
        boolean passedSecurityCheck
) {
    public CaseEvaluationResult {
        retrievedChunkIds = (retrievedChunkIds != null) ? Collections.unmodifiableList(retrievedChunkIds) : Collections.emptyList();
    }
}
