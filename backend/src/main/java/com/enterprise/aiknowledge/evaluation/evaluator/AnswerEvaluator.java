package com.enterprise.aiknowledge.evaluation.evaluator;

import com.enterprise.aiknowledge.evaluation.model.EvaluationCase;
import com.enterprise.aiknowledge.evaluation.model.GenerationMetrics;
import com.enterprise.aiknowledge.service.RagService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic evaluator for RAG answer quality:
 * Answer Relevance, Context Faithfulness / Groundedness, and Conservative Unanswerable Rejection.
 */
@Component
public class AnswerEvaluator {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
            "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
            "below", "between", "both", "but", "by", "can", "can't", "cannot", "could",
            "did", "do", "does", "doing", "don't", "down", "during", "each", "few", "for",
            "from", "further", "had", "has", "have", "having", "he", "her", "here", "hers",
            "herself", "him", "himself", "his", "how", "i", "if", "in", "into", "is",
            "it", "its", "itself", "let's", "me", "more", "most", "my", "myself", "no",
            "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought",
            "our", "ours", "ourselves", "out", "over", "own", "same", "she", "should",
            "so", "some", "such", "than", "that", "the", "their", "theirs", "them",
            "themselves", "then", "there", "these", "they", "this", "those", "through",
            "to", "too", "under", "until", "up", "very", "was", "we", "were", "what",
            "when", "where", "which", "while", "who", "whom", "why", "with", "would",
            "you", "your", "yours", "yourself", "yourselves"
    );

    private static final Pattern WORD_SPLITTER = Pattern.compile("[^a-zA-Z0-9]+");

    /**
     * Evaluates generation metrics for an answer against the test case and retrieved context.
     *
     * @param answer           actual answer returned by RAG
     * @param retrievedContext text context supplied to the generator
     * @param evaluationCase   ground truth specification
     * @param sourcesCount     number of sources referenced
     * @return structured {@link GenerationMetrics}
     */
    public GenerationMetrics evaluate(
            String answer,
            String retrievedContext,
            EvaluationCase evaluationCase,
            int sourcesCount) {

        String safeAnswer = (answer != null) ? answer.trim() : "";
        String safeContext = (retrievedContext != null) ? retrievedContext.trim() : "";

        boolean unanswerableCorrect = checkUnanswerableHandling(safeAnswer, evaluationCase.isUnanswerable(), sourcesCount);

        // If this was an unanswerable question
        if (evaluationCase.isUnanswerable()) {
            double score = unanswerableCorrect ? 1.0 : 0.0;
            return new GenerationMetrics(
                    score,
                    score,
                    unanswerableCorrect,
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        // 1. Relevance: Concept coverage
        List<String> missingConcepts = new ArrayList<>();
        double relevanceScore = calculateRelevance(safeAnswer, evaluationCase, missingConcepts);

        // 2. Faithfulness: Context token support & unsupported claim detection
        List<String> unsupportedTokens = new ArrayList<>();
        double faithfulnessScore = calculateFaithfulness(safeAnswer, safeContext, unsupportedTokens);

        return new GenerationMetrics(
                relevanceScore,
                faithfulnessScore,
                unanswerableCorrect,
                missingConcepts,
                unsupportedTokens
        );
    }

    /**
     * Checks if conservative behavior was properly exhibited for unanswerable queries.
     */
    public boolean checkUnanswerableHandling(String answer, boolean isExpectedUnanswerable, int sourcesCount) {
        String lowerAnswer = answer.toLowerCase(Locale.ROOT);
        boolean indicatesUnavailable = lowerAnswer.contains("not available in the provided documents")
                || lowerAnswer.contains("information is not available")
                || lowerAnswer.equalsIgnoreCase(RagService.DEFAULT_INSUFFICIENT_CONTEXT_MESSAGE.toLowerCase(Locale.ROOT));

        if (isExpectedUnanswerable) {
            return indicatesUnavailable && sourcesCount == 0;
        } else {
            // For an answerable query, returning "not available" indicates a retrieval or generation failure
            return !indicatesUnavailable;
        }
    }

    /**
     * Calculates relevance score:
     * Evaluates presence of required ground-truth concepts or reference content tokens.
     */
    public double calculateRelevance(String answer, EvaluationCase evaluationCase, List<String> missingConcepts) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        String lowerAnswer = answer.toLowerCase(Locale.ROOT);

        if (!evaluationCase.requiredConcepts().isEmpty()) {
            int found = 0;
            for (String concept : evaluationCase.requiredConcepts()) {
                if (lowerAnswer.contains(concept.toLowerCase(Locale.ROOT))) {
                    found++;
                } else {
                    missingConcepts.add(concept);
                }
            }
            return (double) found / evaluationCase.requiredConcepts().size();
        }

        // Fallback to reference answer keyword overlap if no explicit concepts specified
        if (evaluationCase.referenceAnswer() != null && !evaluationCase.referenceAnswer().isBlank()) {
            Set<String> refTokens = extractSignificantTokens(evaluationCase.referenceAnswer());
            if (refTokens.isEmpty()) {
                return 1.0;
            }
            Set<String> ansTokens = extractSignificantTokens(answer);
            long hits = refTokens.stream().filter(ansTokens::contains).count();
            return (double) hits / refTokens.size();
        }

        return 1.0; // If no reference criteria given, assume relevant if non-blank
    }

    /**
     * Calculates faithfulness score:
     * Measures the fraction of significant content words in the answer supported by the retrieved context.
     * Detects unsupported factual claims.
     */
    public double calculateFaithfulness(String answer, String context, List<String> unsupportedTokens) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }
        if (context == null || context.isBlank()) {
            return 0.0;
        }

        Set<String> answerTokens = extractSignificantTokens(answer);
        if (answerTokens.isEmpty()) {
            return 1.0;
        }

        Set<String> contextTokens = extractSignificantTokens(context);

        int supported = 0;
        for (String token : answerTokens) {
            if (contextTokens.contains(token)) {
                supported++;
            } else {
                unsupportedTokens.add(token);
            }
        }

        return (double) supported / answerTokens.size();
    }

    /**
     * Extracts lower-cased significant content words (excluding stopwords and single-character words).
     */
    public Set<String> extractSignificantTokens(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> tokens = new LinkedHashSet<>();
        String[] words = WORD_SPLITTER.split(text.toLowerCase(Locale.ROOT));

        for (String word : words) {
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }

        return tokens;
    }
}
