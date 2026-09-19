package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.exception.GenerationServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GeminiGenerationService Unit Tests")
class GeminiGenerationServiceTest {

    @Test
    @DisplayName("generateAnswer successfully returns generated answer")
    void generateAnswer_success() {
        GeminiGenerationService service = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> {
                    assertTrue(instruction.contains("enterprise knowledge assistant"));
                    assertTrue(context.contains("[SOURCE 1]"));
                    assertTrue(question.contains("leave policy"));
                    return "Employees receive 20 days of annual leave.";
                }
        );

        String answer = service.generateAnswer(
                "You are an enterprise knowledge assistant.",
                "[SOURCE 1] Policy details",
                "What is the leave policy?"
        );

        assertEquals("Employees receive 20 days of annual leave.", answer);
        assertEquals("gemini-2.5-flash", service.getModel());
        assertEquals(0.2f, service.getTemperature());
        assertEquals(1024, service.getMaxOutputTokens());
    }

    @Test
    @DisplayName("generateAnswer rejects null or blank question")
    void generateAnswer_rejectsInvalidQuestion() {
        GeminiGenerationService service = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> "Answer"
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.generateAnswer("Instruction", "Context", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateAnswer("Instruction", "Context", "   "));
    }

    @Test
    @DisplayName("generateAnswer throws GenerationServiceException on empty or blank response")
    void generateAnswer_throwsOnEmptyResponse() {
        GeminiGenerationService serviceWithBlank = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> "   "
        );

        assertThrows(GenerationServiceException.class,
                () -> serviceWithBlank.generateAnswer("Instruction", "Context", "Valid question?"));

        GeminiGenerationService serviceWithNull = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> null
        );

        assertThrows(GenerationServiceException.class,
                () -> serviceWithNull.generateAnswer("Instruction", "Context", "Valid question?"));
    }

    @Test
    @DisplayName("generateAnswer retries on transient errors and succeeds when resolved")
    void generateAnswer_retriesOnTransientErrorAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiGenerationService service = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new RuntimeException("429 RESOURCE_EXHAUSTED quota exceeded");
                    }
                    return "Grounded answer after retry";
                }
        );

        String answer = service.generateAnswer("Instruction", "Context", "Question?");
        assertEquals("Grounded answer after retry", answer);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("generateAnswer throws GenerationServiceException when max retries are exceeded")
    void generateAnswer_throwsWhenMaxRetriesExceeded() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiGenerationService service = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 2, 10, "mock-api-key",
                (instruction, context, question) -> {
                    attempts.incrementAndGet();
                    throw new IOException("Connection timeout to Gemini API");
                }
        );

        GenerationServiceException ex = assertThrows(GenerationServiceException.class,
                () -> service.generateAnswer("Instruction", "Context", "Question?"));

        assertTrue(ex.getMessage().contains("attempts") || ex.getMessage().contains("failed"));
        assertEquals(3, attempts.get()); // initial attempt + 2 retries = 3
    }

    @Test
    @DisplayName("generateAnswer fails immediately on permanent client error without retrying")
    void generateAnswer_failsImmediatelyOnPermanentError() {
        AtomicInteger attempts = new AtomicInteger(0);

        GeminiGenerationService service = new GeminiGenerationService(
                "gemini-2.5-flash", 0.2f, 1024, 3, 10, "mock-api-key",
                (instruction, context, question) -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("400 Bad Request: Invalid parameter");
                }
        );

        assertThrows(GenerationServiceException.class,
                () -> service.generateAnswer("Instruction", "Context", "Question?"));

        assertEquals(1, attempts.get()); // Only 1 attempt made, no retries
    }

    @Test
    @DisplayName("constructor validates configuration constraints")
    void constructor_validatesParameters() {
        assertThrows(IllegalArgumentException.class, () ->
                new GeminiGenerationService("", 0.2f, 1024, 3, 10, "key", null));
        assertThrows(IllegalArgumentException.class, () ->
                new GeminiGenerationService("model", -0.1f, 1024, 3, 10, "key", null));
        assertThrows(IllegalArgumentException.class, () ->
                new GeminiGenerationService("model", 2.1f, 1024, 3, 10, "key", null));
        assertThrows(IllegalArgumentException.class, () ->
                new GeminiGenerationService("model", 0.2f, 0, 3, 10, "key", null));
        assertThrows(IllegalArgumentException.class, () ->
                new GeminiGenerationService("model", 0.2f, 1024, -1, 10, "key", null));
    }
}
