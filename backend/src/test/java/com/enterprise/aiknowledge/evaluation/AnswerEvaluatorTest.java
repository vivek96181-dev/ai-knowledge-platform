package com.enterprise.aiknowledge.evaluation;

import com.enterprise.aiknowledge.evaluation.evaluator.AnswerEvaluator;
import com.enterprise.aiknowledge.evaluation.model.EvaluationCase;
import com.enterprise.aiknowledge.evaluation.model.GenerationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnswerEvaluator Unit Tests")
class AnswerEvaluatorTest {

    private AnswerEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerEvaluator();
    }

    @Test
    @DisplayName("evaluate calculates relevance based on required concepts")
    void evaluate_calculatesConceptCoverage() {
        EvaluationCase testCase = new EvaluationCase(
                "case-1",
                "What is the leave policy?",
                List.of(101L),
                Collections.emptyList(),
                "Employees get 20 days annual leave.",
                List.of("20 days", "annual leave", "carry forward"),
                false,
                "user@example.com",
                false
        );

        // Answer contains 2 of the 3 concepts ("20 days", "annual leave")
        String answer = "Employees receive 20 days of annual leave.";
        String context = "Employees receive 20 days of annual leave.";

        GenerationMetrics metrics = evaluator.evaluate(answer, context, testCase, 1);

        assertEquals(2.0 / 3.0, metrics.relevanceScore(), 0.001);
        assertEquals(1, metrics.missingConcepts().size());
        assertEquals("carry forward", metrics.missingConcepts().get(0));
    }

    @Test
    @DisplayName("evaluate calculates faithfulness from retrieved context support")
    void evaluate_calculatesFaithfulnessAndDetectsUnsupportedClaims() {
        EvaluationCase testCase = new EvaluationCase(
                "case-2",
                "What is the policy?",
                List.of(101L),
                Collections.emptyList(),
                "Standard policy",
                Collections.emptyList(),
                false,
                "user@example.com",
                false
        );

        String context = "The annual leave allocation is twenty days.";
        // "Ferrari" and "bonus" are not supported in context
        String answer = "The annual leave allocation is twenty days plus a Ferrari bonus.";

        GenerationMetrics metrics = evaluator.evaluate(answer, context, testCase, 1);

        // Faithfulness is less than 1.0 because Ferrari and bonus are unsupported
        assertTrue(metrics.faithfulnessScore() < 1.0);
        assertTrue(metrics.unsupportedTokens().contains("ferrari") || metrics.unsupportedTokens().contains("bonus"));
    }

    @Test
    @DisplayName("evaluate handles unanswerable questions correctly when conservative message returned")
    void evaluate_unanswerableQuestion_handledCorrectly() {
        EvaluationCase testCase = new EvaluationCase(
                "unans-1",
                "What is the Tokyo office address?",
                Collections.emptyList(),
                Collections.emptyList(),
                "The requested information is not available in the provided documents.",
                List.of("not available"),
                true,
                "user@example.com",
                false
        );

        String answer = "The requested information is not available in the provided documents.";
        GenerationMetrics metrics = evaluator.evaluate(answer, "", testCase, 0);

        assertTrue(metrics.unanswerableHandledCorrectly());
        assertEquals(1.0, metrics.relevanceScore());
        assertEquals(1.0, metrics.faithfulnessScore());
    }

    @Test
    @DisplayName("evaluate detects failure on unanswerable question when model hallucinates an answer")
    void evaluate_unanswerableQuestion_detectsHallucination() {
        EvaluationCase testCase = new EvaluationCase(
                "unans-2",
                "What is the Tokyo office address?",
                Collections.emptyList(),
                Collections.emptyList(),
                "The requested information is not available in the provided documents.",
                List.of("not available"),
                true,
                "user@example.com",
                false
        );

        // Hallucinated answer
        String answer = "The Tokyo office is located in Shibuya 1-2-3.";
        GenerationMetrics metrics = evaluator.evaluate(answer, "", testCase, 0);

        assertFalse(metrics.unanswerableHandledCorrectly());
        assertEquals(0.0, metrics.relevanceScore());
        assertEquals(0.0, metrics.faithfulnessScore());
    }

    @Test
    @DisplayName("extractSignificantTokens ignores stopwords and punctuation")
    void extractSignificantTokens_filtersStopwords() {
        var tokens = evaluator.extractSignificantTokens("This is a simple test, with some punctuation!");

        assertTrue(tokens.contains("simple"));
        assertTrue(tokens.contains("test"));
        assertTrue(tokens.contains("punctuation"));
        assertFalse(tokens.contains("this"));
        assertFalse(tokens.contains("is"));
        assertFalse(tokens.contains("a"));
        assertFalse(tokens.contains("with"));
    }
}
