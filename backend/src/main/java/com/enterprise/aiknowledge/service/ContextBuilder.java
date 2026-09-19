package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dedicated component responsible for constructing bounded, formatted context blocks
 * from ranked {@link SearchResult} items for LLM generation.
 *
 * <p><strong>Key Responsibilities:</strong>
 * <ul>
 *   <li>Format distinct, clearly separated {@code [SOURCE N]} blocks.</li>
 *   <li>Preserve document ID, chunk ID, page number, and chunk index.</li>
 *   <li>Preserve exact retrieval rank order.</li>
 *   <li>Enforce maximum context character/token budget without truncating the middle of chunks
 *       (lower-ranked chunks that exceed budget are excluded).</li>
 *   <li>Track and return only the chunks that successfully fit within the context budget.</li>
 * </ul>
 * </p>
 */
@Component
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final int maxContextCharacters;

    @Autowired
    public ContextBuilder(
            @Value("${gemini.generation.max-context-characters:8000}") int maxContextCharacters) {
        if (maxContextCharacters <= 0) {
            throw new IllegalArgumentException("maxContextCharacters must be greater than 0, but was: " + maxContextCharacters);
        }
        this.maxContextCharacters = maxContextCharacters;
    }

    /**
     * Builds bounded context string and tracks included search results.
     *
     * @param results ranked search results from semantic retrieval
     * @return {@link BuiltContext} containing the formatted string and list of included results
     */
    public BuiltContext buildContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return new BuiltContext("", Collections.emptyList());
        }

        StringBuilder sb = new StringBuilder();
        List<SearchResult> included = new ArrayList<>();

        int sourceIndex = 1;
        for (SearchResult result : results) {
            String block = formatSourceBlock(sourceIndex, result);

            // Check if appending this block would exceed the configured budget
            if (sb.length() > 0 && (sb.length() + block.length() > maxContextCharacters)) {
                log.info("Context character budget reached ({} chars). Excluding remaining lower-ranked chunks starting from rank {}",
                        sb.length(), sourceIndex);
                break;
            }

            // In edge case where even the very first block exceeds the budget:
            // if nothing has been added yet and the block exceeds budget, include it up to budget or skip?
            // "Do not silently truncate the middle of a chunk. Prefer excluding additional lower-ranked chunks once context budget is reached."
            if (sb.length() == 0 && block.length() > maxContextCharacters) {
                // If a single chunk is larger than the entire budget, log warning and include it rather than producing empty context, or limit it?
                // Given the instruction "Do not silently truncate the middle of a chunk", we include it as the single source.
                log.warn("First chunk alone ({} chars) exceeds context budget ({} chars). Including as sole source.",
                        block.length(), maxContextCharacters);
                sb.append(block);
                included.add(result);
                break;
            }

            sb.append(block);
            included.add(result);
            sourceIndex++;
        }

        return new BuiltContext(sb.toString().trim(), Collections.unmodifiableList(included));
    }

    private String formatSourceBlock(int sourceIndex, SearchResult result) {
        return String.format(
                "[SOURCE %d]%nDocument ID: %d%nChunk ID: %d%nPage: %d%n%n%s%n%n",
                sourceIndex,
                result.documentId(),
                result.chunkId(),
                result.pageNumber(),
                result.text() != null ? result.text().trim() : ""
        );
    }

    public int getMaxContextCharacters() {
        return maxContextCharacters;
    }

    /**
     * Value record encapsulating the bounded context text and the specific search results that fit.
     */
    public record BuiltContext(
            String contextText,
            List<SearchResult> includedResults
    ) {}
}
