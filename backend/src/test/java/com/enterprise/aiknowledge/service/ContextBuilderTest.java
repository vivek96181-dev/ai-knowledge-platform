package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContextBuilder Unit Tests")
class ContextBuilderTest {

    @Test
    @DisplayName("buildContext formats ranked chunks with metadata and preserves order")
    void buildContext_formatsChunksAndPreservesOrder() {
        ContextBuilder contextBuilder = new ContextBuilder(8000);

        List<SearchResult> results = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "First chunk text about policies."),
                new SearchResult(10L, 102L, 2, 1, 0.88f, "Second chunk text about leaves.")
        );

        ContextBuilder.BuiltContext context = contextBuilder.buildContext(results);

        assertNotNull(context);
        assertEquals(2, context.includedResults().size());
        assertEquals(101L, context.includedResults().get(0).chunkId());
        assertEquals(102L, context.includedResults().get(1).chunkId());

        String text = context.contextText();
        assertTrue(text.contains("[SOURCE 1]"));
        assertTrue(text.contains("Document ID: 10"));
        assertTrue(text.contains("Chunk ID: 101"));
        assertTrue(text.contains("Page: 1"));
        assertTrue(text.contains("First chunk text about policies."));

        assertTrue(text.contains("[SOURCE 2]"));
        assertTrue(text.contains("Chunk ID: 102"));
        assertTrue(text.contains("Page: 2"));
        assertTrue(text.contains("Second chunk text about leaves."));

        // Verify order: SOURCE 1 comes before SOURCE 2
        assertTrue(text.indexOf("[SOURCE 1]") < text.indexOf("[SOURCE 2]"));
    }

    @Test
    @DisplayName("buildContext enforces maxContextCharacters budget without middle-chunk truncation")
    void buildContext_enforcesCharacterBudgetExcludingLowerRankedChunks() {
        // Budget only big enough for first chunk block (~90-110 chars)
        ContextBuilder contextBuilder = new ContextBuilder(120);

        List<SearchResult> results = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "Short text chunk 1."),
                new SearchResult(10L, 102L, 2, 1, 0.88f, "This second chunk will exceed the character budget.")
        );

        ContextBuilder.BuiltContext context = contextBuilder.buildContext(results);

        assertEquals(1, context.includedResults().size());
        assertEquals(101L, context.includedResults().get(0).chunkId());
        assertTrue(context.contextText().contains("[SOURCE 1]"));
        assertFalse(context.contextText().contains("[SOURCE 2]"));
        // Chunk 1 text must be complete (not truncated)
        assertTrue(context.contextText().contains("Short text chunk 1."));
    }

    @Test
    @DisplayName("buildContext includes sole first chunk when first chunk alone exceeds budget")
    void buildContext_includesFirstChunkWhenAloneExceedsBudget() {
        ContextBuilder contextBuilder = new ContextBuilder(50);

        List<SearchResult> results = List.of(
                new SearchResult(10L, 101L, 1, 0, 0.95f, "This chunk alone is definitely longer than 50 characters."),
                new SearchResult(10L, 102L, 2, 1, 0.88f, "Second chunk.")
        );

        ContextBuilder.BuiltContext context = contextBuilder.buildContext(results);

        assertEquals(1, context.includedResults().size());
        assertEquals(101L, context.includedResults().get(0).chunkId());
        assertTrue(context.contextText().contains("[SOURCE 1]"));
        assertFalse(context.contextText().contains("[SOURCE 2]"));
    }

    @Test
    @DisplayName("buildContext handles null and empty results gracefully")
    void buildContext_handlesEmptyAndNull() {
        ContextBuilder contextBuilder = new ContextBuilder(8000);

        ContextBuilder.BuiltContext emptyResult = contextBuilder.buildContext(Collections.emptyList());
        assertEquals("", emptyResult.contextText());
        assertTrue(emptyResult.includedResults().isEmpty());

        ContextBuilder.BuiltContext nullResult = contextBuilder.buildContext(null);
        assertEquals("", nullResult.contextText());
        assertTrue(nullResult.includedResults().isEmpty());
    }

    @Test
    @DisplayName("constructor rejects non-positive maxContextCharacters")
    void constructor_rejectsInvalidBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ContextBuilder(0));
        assertThrows(IllegalArgumentException.class, () -> new ContextBuilder(-100));
    }
}
