package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Service orchestrating Retrieval-Augmented Generation (RAG).
 *
 * <p><strong>Orchestration Flow:</strong>
 * <ol>
 *   <li>Validate request parameters (query non-blank, max length 1000, topK within bounds).</li>
 *   <li>Execute authorized semantic vector search via {@link SemanticSearchService}.</li>
 *   <li>Evaluate retrieved context: if zero chunks retrieved, return conservative non-hallucinatory answer.</li>
 *   <li>Construct bounded, formatted context via {@link ContextBuilder} preserving chunk rank.</li>
 *   <li>Generate grounded answer via {@link GenerationService} using explicit system instructions.</li>
 *   <li>Map included chunks to structured {@link RagSource} references preserving ranking order.</li>
 *   <li>Return {@link RagResponse} without exposing embedding vectors or internal filesystem paths.</li>
 * </ol>
 * </p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    public static final String DEFAULT_INSUFFICIENT_CONTEXT_MESSAGE =
            "The requested information is not available in the provided documents.";

    public static final String SYSTEM_INSTRUCTION =
            "You are an enterprise knowledge assistant.\n\n"
            + "Answer the user's question using only the supplied context.\n"
            + "Do not use information that is not present in the context.\n"
            + "Do not invent facts.\n"
            + "When the context does not contain enough information to answer the question, "
            + "clearly state that the information is not available in the provided documents.\n"
            + "Keep the answer concise and factual.\n"
            + "Do not claim that something is present in the documents unless it is supported by the supplied context.";

    private final SearchService searchService;
    private final ContextBuilder contextBuilder;
    private final GenerationService generationService;

    @Autowired
    public RagService(
            SearchService searchService,
            ContextBuilder contextBuilder,
            GenerationService generationService) {
        this.searchService = searchService;
        this.contextBuilder = contextBuilder;
        this.generationService = generationService;
    }

    /**
     * Answers a natural-language question grounded strictly in the user's authorized documents.
     *
     * @param request          RAG request containing user query and optional topK
     * @param currentUserEmail email of the authenticated principal from JWT
     * @param isAdmin          whether the authenticated user has ROLE_ADMIN
     * @return structured RAG response containing grounded answer and sources
     */
    public RagResponse ask(RagRequest request, String currentUserEmail, boolean isAdmin) {
        // Step 1: Input validation
        if (request == null || request.query() == null || request.query().trim().isBlank()) {
            throw new IllegalArgumentException("Query cannot be blank");
        }

        String trimmedQuery = request.query().trim();
        if (trimmedQuery.length() > 1000) {
            throw new IllegalArgumentException("Query cannot exceed 1000 characters");
        }

        int resolvedTopK = (request.topK() != null) ? request.topK() : searchService.getDefaultTopK();
        if (resolvedTopK < 1 || resolvedTopK > searchService.getMaxTopK()) {
            throw new IllegalArgumentException(String.format(
                    "topK must be between 1 and %d, but was: %d",
                    searchService.getMaxTopK(), resolvedTopK));
        }

        log.info("Starting RAG request (user: {}, role: {}, topK: {})",
                currentUserEmail, isAdmin ? "ADMIN" : "USER", resolvedTopK);

        // Step 2: Retrieve authorized chunks using existing SemanticSearchService
        SearchRequest searchRequest = new SearchRequest(trimmedQuery, resolvedTopK);
        SearchResponse searchResponse = searchService.search(searchRequest, currentUserEmail, isAdmin);
        List<SearchResult> searchResults = searchResponse.results();

        log.info("RAG retrieval finished: {} relevant chunks retrieved",
                searchResults != null ? searchResults.size() : 0);

        // Step 3: Conservative check for insufficient context
        if (searchResults == null || searchResults.isEmpty()) {
            log.info("No relevant chunks found. Returning conservative response without calling LLM.");
            return new RagResponse(trimmedQuery, DEFAULT_INSUFFICIENT_CONTEXT_MESSAGE, Collections.emptyList());
        }

        // Step 4: Build bounded context preserving rank order
        ContextBuilder.BuiltContext builtContext = contextBuilder.buildContext(searchResults);
        if (builtContext.includedResults().isEmpty() || builtContext.contextText().isBlank()) {
            log.info("Context builder produced empty context. Returning conservative response without calling LLM.");
            return new RagResponse(trimmedQuery, DEFAULT_INSUFFICIENT_CONTEXT_MESSAGE, Collections.emptyList());
        }

        log.info("Built bounded RAG context ({} characters from {} chunks)",
                builtContext.contextText().length(), builtContext.includedResults().size());

        // Step 5: Generate grounded answer via Gemini
        String answer = generationService.generateAnswer(
                SYSTEM_INSTRUCTION,
                builtContext.contextText(),
                trimmedQuery
        );

        // Step 6: Map only the included search results to source references
        List<RagSource> sources = builtContext.includedResults().stream()
                .map(res -> new RagSource(
                        res.documentId(),
                        res.chunkId(),
                        res.pageNumber(),
                        res.chunkIndex(),
                        res.score()
                ))
                .toList();

        log.info("RAG generation completed successfully with {} sources", sources.size());

        return new RagResponse(trimmedQuery, answer, sources);
    }
}
