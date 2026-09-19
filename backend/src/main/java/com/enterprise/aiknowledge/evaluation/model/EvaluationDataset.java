package com.enterprise.aiknowledge.evaluation.model;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates a versioned dataset of evaluation test cases.
 *
 * @param name        dataset name (e.g. "rag-baseline-v1")
 * @param version     dataset semantic version
 * @param description brief summary of the dataset scope and fixture requirements
 * @param cases       list of individual evaluation test cases
 */
public record EvaluationDataset(
        String name,
        String version,
        String description,
        List<EvaluationCase> cases
) {
    public EvaluationDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("EvaluationDataset name cannot be blank");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("EvaluationDataset must contain at least one evaluation case");
        }
        cases = Collections.unmodifiableList(cases);
    }
}
