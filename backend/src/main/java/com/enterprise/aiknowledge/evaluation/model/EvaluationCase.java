package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.List;

/**
 * Represents a single deterministic test case within an evaluation dataset.
 *
 * @param id                  unique identifier for the test case (e.g. "leave-policy-001")
 * @param question            the natural-language question being asked
 * @param expectedChunkIds    list of chunk IDs that are relevant to answering this question
 * @param expectedDocumentIds optional list of parent document IDs expected
 * @param referenceAnswer     optional canonical reference answer
 * @param requiredConcepts    optional list of concepts or key phrases expected in the answer
 * @param isUnanswerable      true if this question cannot be answered from the corpus
 * @param userEmail           authenticated user identity used for evaluating multi-tenant isolation
 * @param isAdmin             whether the user acts with ROLE_ADMIN
 */
public record EvaluationCase(
        String id,
        String question,
        List<Long> expectedChunkIds,
        List<Long> expectedDocumentIds,
        String referenceAnswer,
        List<String> requiredConcepts,
        boolean isUnanswerable,
        String userEmail,
        boolean isAdmin
) {
    public EvaluationCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("EvaluationCase id cannot be blank");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("EvaluationCase question cannot be blank");
        }
        expectedChunkIds = (expectedChunkIds != null) ? List.copyOf(expectedChunkIds) : Collections.emptyList();
        expectedDocumentIds = (expectedDocumentIds != null) ? List.copyOf(expectedDocumentIds) : Collections.emptyList();
        requiredConcepts = (requiredConcepts != null) ? List.copyOf(requiredConcepts) : Collections.emptyList();
        if (userEmail == null || userEmail.isBlank()) {
            userEmail = "eval_user@example.com";
        }
    }
}
