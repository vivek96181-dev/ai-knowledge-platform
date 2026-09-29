package com.enterprise.aiknowledge.evaluation.runner;

import com.enterprise.aiknowledge.dto.*;
import com.enterprise.aiknowledge.evaluation.evaluator.AnswerEvaluator;
import com.enterprise.aiknowledge.evaluation.evaluator.RetrievalEvaluator;
import com.enterprise.aiknowledge.evaluation.model.*;
import com.enterprise.aiknowledge.service.HybridSearchService;
import com.enterprise.aiknowledge.service.RagService;
import com.enterprise.aiknowledge.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrator service running evaluation suites across retrieval and RAG generation pipelines.
 */
@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final SearchService searchService;
    private final RagService ragService;
    private final RetrievalEvaluator retrievalEvaluator;
    private final AnswerEvaluator answerEvaluator;
    private final EvaluationDatasetLoader datasetLoader;
    private final HybridSearchService hybridSearchService;

    @Autowired
    public EvaluationRunner(
            SearchService searchService,
            RagService ragService,
            RetrievalEvaluator retrievalEvaluator,
            AnswerEvaluator answerEvaluator,
            EvaluationDatasetLoader datasetLoader,
            @Autowired(required = false) HybridSearchService hybridSearchService) {
        this.searchService = searchService;
        this.ragService = ragService;
        this.retrievalEvaluator = retrievalEvaluator;
        this.answerEvaluator = answerEvaluator;
        this.datasetLoader = datasetLoader;
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * Backward-compatible constructor for unit testing.
     */
    public EvaluationRunner(
            SearchService searchService,
            RagService ragService,
            RetrievalEvaluator retrievalEvaluator,
            AnswerEvaluator answerEvaluator,
            EvaluationDatasetLoader datasetLoader) {
        this(searchService, ragService, retrievalEvaluator, answerEvaluator, datasetLoader, null);
    }

    /**
     * Executes evaluation against the default bundled dataset using standard topK=5.
     */
    public EvaluationSummary runDefaultEvaluation() {
        EvaluationDataset dataset = datasetLoader.loadDefaultDataset();
        return runEvaluation(dataset, 5);
    }

    /**
     * Executes evaluation against a provided dataset with the specified topK parameter.
     *
     * @param dataset evaluation dataset to execute
     * @param topK    number of top documents to evaluate for retrieval
     * @return aggregated {@link EvaluationSummary}
     */
    public EvaluationSummary runEvaluation(EvaluationDataset dataset, int topK) {
        if (dataset == null || dataset.cases().isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset cannot be null or empty");
        }

        log.info("Starting RAG evaluation for dataset '{}' ({} cases, topK: {})",
                dataset.name(), dataset.cases().size(), topK);

        List<CaseEvaluationResult> caseResults = new ArrayList<>();
        int securityViolations = 0;

        for (EvaluationCase testCase : dataset.cases()) {
            log.debug("Evaluating case: {} - '{}'", testCase.id(), testCase.question());

            // 1. Execute Retrieval
            SearchRequest searchRequest = new SearchRequest(testCase.question(), topK);
            SearchResponse searchResponse = searchService.search(
                    searchRequest,
                    testCase.userEmail(),
                    testCase.isAdmin()
            );

            List<SearchResult> searchResults = searchResponse.results() != null ? searchResponse.results() : Collections.emptyList();
            List<Long> retrievedChunkIds = searchResults.stream().map(SearchResult::chunkId).toList();

            // Evaluate Retrieval Metrics
            RetrievalMetrics retrievalMetrics = retrievalEvaluator.evaluate(
                    retrievedChunkIds,
                    testCase.expectedChunkIds()
            );

            // 2. Execute RAG
            RagRequest ragRequest = new RagRequest(testCase.question(), topK);
            RagResponse ragResponse = ragService.ask(
                    ragRequest,
                    testCase.userEmail(),
                    testCase.isAdmin()
            );

            // Concatenate retrieved chunks as context string for faithfulness evaluation
            String combinedContext = searchResults.stream()
                    .map(SearchResult::text)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(" "));

            // Evaluate Generation Metrics
            GenerationMetrics generationMetrics = answerEvaluator.evaluate(
                    ragResponse.answer(),
                    combinedContext,
                    testCase,
                    ragResponse.sources() != null ? ragResponse.sources().size() : 0
            );

            // 3. Multi-Tenant Security Check
            boolean passedSecurityCheck = verifySecurityCompliance(testCase, searchResults, ragResponse);
            if (!passedSecurityCheck) {
                securityViolations++;
                log.warn("Security policy violation detected in case: {}", testCase.id());
            }

            caseResults.add(new CaseEvaluationResult(
                    testCase.id(),
                    testCase.question(),
                    retrievalMetrics,
                    generationMetrics,
                    retrievedChunkIds,
                    ragResponse.answer(),
                    passedSecurityCheck
            ));
        }

        // Aggregate summary metrics
        EvaluationSummary summary = aggregateResults(dataset.name(), caseResults, securityViolations);
        log.info("{}", summary.toFormattedReport());

        return summary;
    }

    /**
     * Executes side-by-side retrieval evaluation comparing Baseline (Hybrid Search without Reranking)
     * versus Experiment (Hybrid Search with Reranking).
     *
     * @param dataset dataset containing evaluation cases
     * @param topK    number of top documents to retrieve
     * @return comparison summary containing baseline and experimental metrics
     */
    public RetrievalComparisonSummary compareHybridVsReranked(EvaluationDataset dataset, int topK) {
        if (hybridSearchService == null) {
            throw new IllegalStateException("HybridSearchService is required for hybrid vs reranking evaluation");
        }
        if (dataset == null || dataset.cases().isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset cannot be null or empty");
        }

        log.info("Starting Hybrid vs Reranking evaluation for dataset '{}' ({} cases, topK: {})",
                dataset.name(), dataset.cases().size(), topK);

        List<RetrievalMetrics> baselineMetricsList = new ArrayList<>();
        List<RetrievalMetrics> rerankedMetricsList = new ArrayList<>();

        for (EvaluationCase testCase : dataset.cases()) {
            // 1. Baseline: Hybrid Search without reranking
            SearchRequest baselineRequest = new SearchRequest(testCase.question(), topK, SearchMode.HYBRID, false);
            SearchResponse baselineResponse = hybridSearchService.search(
                    baselineRequest,
                    testCase.userEmail(),
                    testCase.isAdmin()
            );
            List<Long> baselineIds = baselineResponse.results() != null
                    ? baselineResponse.results().stream().map(SearchResult::chunkId).toList()
                    : Collections.emptyList();
            baselineMetricsList.add(retrievalEvaluator.evaluate(baselineIds, testCase.expectedChunkIds()));

            // 2. Experiment: Hybrid Search WITH reranking
            SearchRequest rerankedRequest = new SearchRequest(testCase.question(), topK, SearchMode.HYBRID, true);
            SearchResponse rerankedResponse = hybridSearchService.search(
                    rerankedRequest,
                    testCase.userEmail(),
                    testCase.isAdmin()
            );
            List<Long> rerankedIds = rerankedResponse.results() != null
                    ? rerankedResponse.results().stream().map(SearchResult::chunkId).toList()
                    : Collections.emptyList();
            rerankedMetricsList.add(retrievalEvaluator.evaluate(rerankedIds, testCase.expectedChunkIds()));
        }

        int totalCases = dataset.cases().size();
        Set<Integer> kValues = baselineMetricsList.get(0).recallAtK().keySet();

        Map<Integer, Double> meanRecallBaseline = new LinkedHashMap<>();
        Map<Integer, Double> meanRecallReranked = new LinkedHashMap<>();
        Map<Integer, Double> meanPrecBaseline = new LinkedHashMap<>();
        Map<Integer, Double> meanPrecReranked = new LinkedHashMap<>();

        for (int k : kValues) {
            meanRecallBaseline.put(k, baselineMetricsList.stream().mapToDouble(m -> m.recallAtK().getOrDefault(k, 0.0)).sum() / totalCases);
            meanRecallReranked.put(k, rerankedMetricsList.stream().mapToDouble(m -> m.recallAtK().getOrDefault(k, 0.0)).sum() / totalCases);
            meanPrecBaseline.put(k, baselineMetricsList.stream().mapToDouble(m -> m.precisionAtK().getOrDefault(k, 0.0)).sum() / totalCases);
            meanPrecReranked.put(k, rerankedMetricsList.stream().mapToDouble(m -> m.precisionAtK().getOrDefault(k, 0.0)).sum() / totalCases);
        }

        double mrrBaseline = baselineMetricsList.stream().mapToDouble(RetrievalMetrics::reciprocalRank).sum() / totalCases;
        double mrrReranked = rerankedMetricsList.stream().mapToDouble(RetrievalMetrics::reciprocalRank).sum() / totalCases;

        String report = formatComparisonReport(
                dataset.name(),
                totalCases,
                meanRecallBaseline,
                meanRecallReranked,
                meanPrecBaseline,
                meanPrecReranked,
                mrrBaseline,
                mrrReranked
        );

        log.info("{}", report);

        return new RetrievalComparisonSummary(
                dataset.name(),
                totalCases,
                meanRecallBaseline,
                meanRecallReranked,
                meanPrecBaseline,
                meanPrecReranked,
                mrrBaseline,
                mrrReranked,
                report
        );
    }

    private String formatComparisonReport(
            String datasetName,
            int totalCases,
            Map<Integer, Double> recallBase,
            Map<Integer, Double> recallRerank,
            Map<Integer, Double> precBase,
            Map<Integer, Double> precRerank,
            double mrrBase,
            double mrrRerank) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=======================================================\n");
        sb.append("RETRIEVAL EVALUATION COMPARISON: HYBRID vs HYBRID+RERANKING\n");
        sb.append(String.format("Dataset: %s | Total Queries: %d\n", datasetName, totalCases));
        sb.append("-------------------------------------------------------\n");
        sb.append(String.format("%-15s | %-16s | %-16s\n", "Metric", "Hybrid (Baseline)", "Hybrid + Rerank"));
        sb.append("-------------------------------------------------------\n");

        for (Integer k : recallBase.keySet()) {
            sb.append(String.format("Recall@%-9d | %-16.4f | %-16.4f\n",
                    k, recallBase.getOrDefault(k, 0.0), recallRerank.getOrDefault(k, 0.0)));
        }
        for (Integer k : precBase.keySet()) {
            sb.append(String.format("Precision@%-6d | %-16.4f | %-16.4f\n",
                    k, precBase.getOrDefault(k, 0.0), precRerank.getOrDefault(k, 0.0)));
        }
        sb.append(String.format("%-15s | %-16.4f | %-16.4f\n", "MRR", mrrBase, mrrRerank));
        sb.append("=======================================================\n");

        return sb.toString();
    }

    /**
     * Confirms that unauthorized document chunks never appear for standard users.
     */
    private boolean verifySecurityCompliance(
            EvaluationCase testCase,
            List<SearchResult> searchResults,
            RagResponse ragResponse) {

        // For unanswerable or cross-tenant attack cases, 0 unauthorized chunks should be retrieved
        if (testCase.isUnanswerable() && !testCase.isAdmin()) {
            if (!searchResults.isEmpty() || !ragResponse.sources().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Aggregates individual case evaluation metrics into summary averages.
     */
    public EvaluationSummary aggregateResults(
            String datasetName,
            List<CaseEvaluationResult> results,
            int securityViolations) {

        if (results == null || results.isEmpty()) {
            return new EvaluationSummary(
                    datasetName, 0, Collections.emptyMap(), Collections.emptyMap(),
                    0.0, 0.0, 0.0, 0.0, securityViolations, Collections.emptyList()
            );
        }

        int totalCases = results.size();
        Set<Integer> kValues = results.get(0).retrieval().recallAtK().keySet();

        Map<Integer, Double> meanRecallAtK = new LinkedHashMap<>();
        Map<Integer, Double> meanPrecisionAtK = new LinkedHashMap<>();

        for (int k : kValues) {
            double sumRecall = results.stream().mapToDouble(r -> r.retrieval().recallAtK().getOrDefault(k, 0.0)).sum();
            double sumPrec = results.stream().mapToDouble(r -> r.retrieval().precisionAtK().getOrDefault(k, 0.0)).sum();
            meanRecallAtK.put(k, sumRecall / totalCases);
            meanPrecisionAtK.put(k, sumPrec / totalCases);
        }

        double meanReciprocalRank = results.stream().mapToDouble(r -> r.retrieval().reciprocalRank()).sum() / totalCases;
        double meanRelevance = results.stream().mapToDouble(r -> r.generation().relevanceScore()).sum() / totalCases;
        double meanFaithfulness = results.stream().mapToDouble(r -> r.generation().faithfulnessScore()).sum() / totalCases;

        long unanswerableCount = results.stream().filter(r -> r.generation().unanswerableHandledCorrectly()).count();
        double unanswerableAccuracy = (double) unanswerableCount / totalCases;

        return new EvaluationSummary(
                datasetName,
                totalCases,
                meanRecallAtK,
                meanPrecisionAtK,
                meanReciprocalRank,
                meanRelevance,
                meanFaithfulness,
                unanswerableAccuracy,
                securityViolations,
                results
        );
    }
}
