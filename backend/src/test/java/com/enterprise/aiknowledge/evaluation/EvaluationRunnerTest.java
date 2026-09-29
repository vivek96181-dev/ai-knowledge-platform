package com.enterprise.aiknowledge.evaluation;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.evaluation.evaluator.AnswerEvaluator;
import com.enterprise.aiknowledge.evaluation.evaluator.RetrievalEvaluator;
import com.enterprise.aiknowledge.evaluation.model.EvaluationCase;
import com.enterprise.aiknowledge.evaluation.model.EvaluationDataset;
import com.enterprise.aiknowledge.evaluation.model.EvaluationSummary;
import com.enterprise.aiknowledge.evaluation.runner.EvaluationDatasetLoader;
import com.enterprise.aiknowledge.evaluation.runner.EvaluationRunner;
import com.enterprise.aiknowledge.service.RagService;
import com.enterprise.aiknowledge.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluationRunner Unit and Integration Tests")
class EvaluationRunnerTest {

    @Mock
    private SearchService searchService;

    @Mock
    private RagService ragService;

    @Mock
    private com.enterprise.aiknowledge.service.HybridSearchService hybridSearchService;

    private EvaluationRunner runner;

    @BeforeEach
    void setUp() {
        RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator(List.of(1, 3, 5));
        AnswerEvaluator answerEvaluator = new AnswerEvaluator();
        EvaluationDatasetLoader datasetLoader = new EvaluationDatasetLoader(null);

        runner = new EvaluationRunner(
                searchService,
                ragService,
                retrievalEvaluator,
                answerEvaluator,
                datasetLoader,
                hybridSearchService
        );
    }

    @Test
    @DisplayName("runEvaluation computes complete summary with retrieval, generation, and formatted report")
    void runEvaluation_computesMetricsAndFormattedReport() {
        // Create 2 evaluation cases: 1 factual, 1 unanswerable
        EvaluationCase case1 = new EvaluationCase(
                "case-1",
                "What is the leave policy?",
                List.of(101L, 102L),
                List.of(10L),
                "Employees get 20 days annual leave and carry forward 5 days.",
                List.of("20 days", "annual leave"),
                false,
                "user@example.com",
                false
        );

        EvaluationCase case2 = new EvaluationCase(
                "case-2",
                "What is the Tokyo office address?",
                Collections.emptyList(),
                Collections.emptyList(),
                "The requested information is not available in the provided documents.",
                List.of("not available"),
                true,
                "user@example.com",
                false
        );

        EvaluationDataset dataset = new EvaluationDataset(
                "test-dataset",
                "1.0.0",
                "Mock dataset for testing",
                List.of(case1, case2)
        );

        // Mock case 1: retrieval returns chunk 101, then 102
        when(searchService.search(argThat(req -> req != null && req.query().contains("leave")), eq("user@example.com"), eq(false)))
                .thenReturn(new SearchResponse("What is the leave policy?", List.of(
                        new SearchResult(10L, 101L, 1, 0, 0.95f, "Employees receive 20 days of annual leave."),
                        new SearchResult(10L, 102L, 2, 1, 0.88f, "Up to 5 unused days can be carried forward.")
                )));

        when(ragService.ask(argThat(req -> req != null && req.query().contains("leave")), eq("user@example.com"), eq(false)))
                .thenReturn(new RagResponse(
                        "What is the leave policy?",
                        "Employees receive 20 days of annual leave.",
                        List.of(
                                new RagSource(10L, 101L, 1, 0, 0.95f),
                                new RagSource(10L, 102L, 2, 1, 0.88f)
                        )
                ));

        // Mock case 2 (unanswerable): 0 chunks retrieved, conservative response returned
        when(searchService.search(argThat(req -> req != null && req.query().contains("Tokyo")), eq("user@example.com"), eq(false)))
                .thenReturn(new SearchResponse("What is the Tokyo office address?", Collections.emptyList()));

        when(ragService.ask(argThat(req -> req != null && req.query().contains("Tokyo")), eq("user@example.com"), eq(false)))
                .thenReturn(new RagResponse(
                        "What is the Tokyo office address?",
                        "The requested information is not available in the provided documents.",
                        Collections.emptyList()
                ));

        EvaluationSummary summary = runner.runEvaluation(dataset, 5);

        assertNotNull(summary);
        assertEquals("test-dataset", summary.datasetName());
        assertEquals(2, summary.totalCases());

        // Verify retrieval metrics:
        // Case 1: 2 relevant chunks (101, 102). In top 1: only 101 (recall=0.5). In top 3: 101, 102 (recall=1.0).
        // Case 2: expected 0 chunks. Retrieved 0. Recall = 1.0.
        // Mean Recall@1: (0.5 + 1.0) / 2 = 0.75
        assertEquals(0.75, summary.meanRecallAtK().get(1), 0.001);
        // Mean Recall@3: (1.0 + 1.0) / 2 = 1.0
        assertEquals(1.0, summary.meanRecallAtK().get(3), 0.001);
        // MRR: Case 1 first rank = 1 (RR=1.0), Case 2 unanswerable (RR=0.0). Mean = (1.0 + 0.0) / 2 = 0.50
        assertEquals(0.50, summary.meanReciprocalRank(), 0.001);

        // Verify generation metrics:
        // Case 1 has 2 required concepts ("20 days", "annual leave") -> both present -> relevance = 1.0
        // Case 2 unanswerable correct -> relevance = 1.0
        assertEquals(1.0, summary.meanAnswerRelevance(), 0.001);

        // Unanswerable accuracy: 100%
        assertEquals(1.0, summary.unanswerableAccuracy(), 0.001);
        assertEquals(0, summary.securityViolations());

        // Verify formatted ASCII summary report
        String report = summary.toFormattedReport();
        assertNotNull(report);
        assertTrue(report.contains("RAG EVALUATION"));
        assertTrue(report.contains("Dataset: test-dataset"));
        assertTrue(report.contains("Recall@1:"));
        assertTrue(report.contains("Recall@3:"));
        assertTrue(report.contains("Recall@5:"));
        assertTrue(report.contains("MRR:"));
        assertTrue(report.contains("Answer Relevance:"));
        assertTrue(report.contains("Faithfulness:"));
        assertTrue(report.contains("Unanswerable Accuracy:"));
        assertTrue(report.contains("Security Violations:   0"));
    }

    @Test
    @DisplayName("runEvaluation flags security violation if unanswerable case retrieves unauthorized chunks")
    void runEvaluation_flagsSecurityViolation() {
        EvaluationCase securityCase = new EvaluationCase(
                "sec-attack-01",
                "What is User B's secret salary?",
                Collections.emptyList(),
                Collections.emptyList(),
                "The requested information is not available in the provided documents.",
                List.of("not available"),
                true,
                "user_a@example.com",
                false
        );

        EvaluationDataset dataset = new EvaluationDataset(
                "security-dataset",
                "1.0.0",
                "Security check dataset",
                List.of(securityCase)
        );

        // Simulate security violation: search erroneously returns User B's chunk
        when(searchService.search(any(), eq("user_a@example.com"), eq(false)))
                .thenReturn(new SearchResponse("Query", List.of(
                        new SearchResult(99L, 999L, 1, 0, 0.90f, "User B top secret salary is $500,000")
                )));

        when(ragService.ask(any(), eq("user_a@example.com"), eq(false)))
                .thenReturn(new RagResponse("Query", "User B top secret salary is $500,000", List.of(
                        new RagSource(99L, 999L, 1, 0, 0.90f)
                )));

        EvaluationSummary summary = runner.runEvaluation(dataset, 5);

        assertEquals(1, summary.securityViolations());
        assertFalse(summary.caseResults().get(0).passedSecurityCheck());
    }

    @Test
    @DisplayName("runDefaultEvaluation runs bundled dataset successfully")
    void runDefaultEvaluation_success() {
        // Stub standard responses for default evaluation queries
        when(searchService.search(any(), anyString(), anyBoolean()))
                .thenReturn(new SearchResponse("Query", Collections.emptyList()));
        when(ragService.ask(any(), anyString(), anyBoolean()))
                .thenReturn(new RagResponse("Query", "The requested information is not available in the provided documents.", Collections.emptyList()));

        EvaluationSummary summary = runner.runDefaultEvaluation();

        assertNotNull(summary);
        assertEquals("rag-baseline-v1", summary.datasetName());
        assertEquals(7, summary.totalCases());
    }

    @Test
    @DisplayName("compareHybridVsReranked evaluates baseline and reranked metrics side-by-side")
    void compareHybridVsReranked_computesComparisonReport() {
        EvaluationCase testCase = new EvaluationCase(
                "case-1",
                "What is the leave policy?",
                List.of(101L, 102L),
                List.of(10L),
                "Leave policy reference",
                List.of("leave"),
                false,
                "user@example.com",
                false
        );

        EvaluationDataset dataset = new EvaluationDataset(
                "compare-dataset",
                "1.0.0",
                "Comparison test dataset",
                List.of(testCase)
        );

        // Baseline (rerank=false): returns chunk 102 first, then 101
        when(hybridSearchService.search(
                argThat(req -> req != null && Boolean.FALSE.equals(req.rerank())),
                eq("user@example.com"),
                eq(false)
        )).thenReturn(new SearchResponse("What is the leave policy?", List.of(
                new SearchResult(10L, 102L, 2, 1, 0.03f, "Chunk 102 text"),
                new SearchResult(10L, 101L, 1, 0, 0.02f, "Chunk 101 text")
        )));

        // Experiment (rerank=true): returns chunk 101 first, then 102
        when(hybridSearchService.search(
                argThat(req -> req != null && Boolean.TRUE.equals(req.rerank())),
                eq("user@example.com"),
                eq(false)
        )).thenReturn(new SearchResponse("What is the leave policy?", List.of(
                new SearchResult(10L, 101L, 1, 0, 0.02f, "Chunk 101 text", 0.95f),
                new SearchResult(10L, 102L, 2, 1, 0.03f, "Chunk 102 text", 0.80f)
        )));

        var comparison = runner.compareHybridVsReranked(dataset, 5);

        assertNotNull(comparison);
        assertEquals("compare-dataset", comparison.datasetName());
        assertEquals(1, comparison.totalQueries());
        assertNotNull(comparison.formattedReport());
        assertTrue(comparison.formattedReport().contains("HYBRID vs HYBRID+RERANKING"));
    }
}
