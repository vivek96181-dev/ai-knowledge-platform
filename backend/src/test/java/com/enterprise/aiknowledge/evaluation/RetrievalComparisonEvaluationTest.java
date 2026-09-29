package com.enterprise.aiknowledge.evaluation;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.evaluation.evaluator.AnswerEvaluator;
import com.enterprise.aiknowledge.evaluation.evaluator.RetrievalEvaluator;
import com.enterprise.aiknowledge.evaluation.model.EvaluationDataset;
import com.enterprise.aiknowledge.evaluation.model.RetrievalComparisonSummary;
import com.enterprise.aiknowledge.evaluation.runner.EvaluationDatasetLoader;
import com.enterprise.aiknowledge.evaluation.runner.EvaluationRunner;
import com.enterprise.aiknowledge.service.HybridSearchService;
import com.enterprise.aiknowledge.service.RagService;
import com.enterprise.aiknowledge.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Retrieval Comparison Evaluation (Hybrid vs Hybrid + Reranking)")
class RetrievalComparisonEvaluationTest {

    @Mock private SearchService searchService;
    @Mock private RagService ragService;
    @Mock private HybridSearchService hybridSearchService;

    @Test
    @DisplayName("Evaluate and compare Hybrid Search baseline against Hybrid + Reranking on bundled dataset")
    void evaluateHybridVsRerankingComparison() {
        RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator(List.of(1, 3, 5));
        AnswerEvaluator answerEvaluator = new AnswerEvaluator();
        EvaluationDatasetLoader datasetLoader = new EvaluationDatasetLoader(null);

        EvaluationRunner runner = new EvaluationRunner(
                searchService,
                ragService,
                retrievalEvaluator,
                answerEvaluator,
                datasetLoader,
                hybridSearchService
        );

        EvaluationDataset dataset = datasetLoader.loadDefaultDataset();

        // Mock Hybrid Baseline (rerank=false):
        // In baseline, RRF retrieves candidate chunks, but sometimes a non-relevant keyword match is at rank 1
        when(hybridSearchService.search(argThat(req -> req != null && Boolean.FALSE.equals(req.rerank())), anyString(), anyBoolean()))
                .thenAnswer(inv -> {
                    SearchRequest req = inv.getArgument(0);
                    String q = req.query();
                    if (q.contains("annual leave")) {
                        // Baseline has chunk 999 (partial match) at rank 1, chunk 101 at rank 2, chunk 102 at rank 3
                        return new SearchResponse(q, List.of(
                                new SearchResult(10L, 999L, 1, 0, 0.032f, "Partial leave mention"),
                                new SearchResult(10L, 101L, 1, 1, 0.030f, "Annual leave policy 20 days"),
                                new SearchResult(10L, 102L, 2, 2, 0.025f, "Carried forward leave 5 days")
                        ));
                    } else if (q.contains("remote")) {
                        // Baseline has 103 at rank 1, 998 at rank 2, 104 at rank 3
                        return new SearchResponse(q, List.of(
                                new SearchResult(11L, 103L, 1, 0, 0.031f, "Laptop and monitor"),
                                new SearchResult(11L, 998L, 2, 1, 0.028f, "Other office guidelines"),
                                new SearchResult(11L, 104L, 2, 2, 0.020f, "500 dollar stipend")
                        ));
                    } else if (q.contains("probation")) {
                        // Baseline has 997 at rank 1, 105 at rank 2
                        return new SearchResponse(q, List.of(
                                new SearchResult(12L, 997L, 1, 0, 0.029f, "Employee onboarding general"),
                                new SearchResult(12L, 105L, 1, 1, 0.027f, "90 days probation period")
                        ));
                    }
                    return new SearchResponse(q, Collections.emptyList());
                });

        // Mock Hybrid + Reranking (rerank=true):
        // In experiment, Gemini reranker correctly promotes the exact answering chunks to rank 1
        when(hybridSearchService.search(argThat(req -> req != null && Boolean.TRUE.equals(req.rerank())), anyString(), anyBoolean()))
                .thenAnswer(inv -> {
                    SearchRequest req = inv.getArgument(0);
                    String q = req.query();
                    if (q.contains("annual leave")) {
                        // Reranker promotes 101 to rank 1 (score 0.98), 102 to rank 2 (0.92), 999 to rank 3 (0.30)
                        return new SearchResponse(q, List.of(
                                new SearchResult(10L, 101L, 1, 1, 0.030f, "Annual leave policy 20 days", 0.98f),
                                new SearchResult(10L, 102L, 2, 2, 0.025f, "Carried forward leave 5 days", 0.92f),
                                new SearchResult(10L, 999L, 1, 0, 0.032f, "Partial leave mention", 0.30f)
                        ));
                    } else if (q.contains("remote")) {
                        // Reranker promotes 103 to rank 1 (0.96), 104 to rank 2 (0.93), 998 to rank 3 (0.25)
                        return new SearchResponse(q, List.of(
                                new SearchResult(11L, 103L, 1, 0, 0.031f, "Laptop and monitor", 0.96f),
                                new SearchResult(11L, 104L, 2, 2, 0.020f, "500 dollar stipend", 0.93f),
                                new SearchResult(11L, 998L, 2, 1, 0.028f, "Other office guidelines", 0.25f)
                        ));
                    } else if (q.contains("probation")) {
                        // Reranker promotes 105 to rank 1 (0.95), 997 to rank 2 (0.20)
                        return new SearchResponse(q, List.of(
                                new SearchResult(12L, 105L, 1, 1, 0.027f, "90 days probation period", 0.95f),
                                new SearchResult(12L, 997L, 1, 0, 0.029f, "Employee onboarding general", 0.20f)
                        ));
                    }
                    return new SearchResponse(q, Collections.emptyList());
                });

        RetrievalComparisonSummary summary = runner.compareHybridVsReranked(dataset, 5);

        System.out.println(summary.formattedReport());

        assertThat(summary.totalQueries()).isEqualTo(7);
        assertThat(summary.mrrReranked()).isGreaterThanOrEqualTo(summary.mrrBaseline());
        assertThat(summary.meanRecallAtKReranked().get(1)).isGreaterThan(summary.meanRecallAtKBaseline().get(1));
        assertThat(summary.meanPrecisionAtKReranked().get(1)).isGreaterThan(summary.meanPrecisionAtKBaseline().get(1));
    }
}
