package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for deterministic, page-aware text chunking.
 *
 * <p><strong>Citation Support:</strong> Every chunk maintains its source {@code pageNumber}
 * and character offsets ({@code characterStart}, {@code characterEnd}) referencing the original page text
 * so future RAG retrieval can generate exact document citations (e.g., "Source: Unit-5-ACD.pdf, Page 7").</p>
 *
 * <p><strong>Word Boundary Preservation:</strong> Slices windows at whitespace or sentence punctuation
 * boundaries within the overlap window to avoid splitting words across chunk boundaries.</p>
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final int targetSize;
    private final int overlapSize;

    public ChunkingService(
            @Value("${chunking.target-size:800}") int targetSize,
            @Value("${chunking.overlap-size:150}") int overlapSize) {
        validateConfiguration(targetSize, overlapSize);
        this.targetSize = targetSize;
        this.overlapSize = overlapSize;
    }

    /**
     * Validates that target-size and overlap-size adhere to reasonable constraints.
     */
    private static void validateConfiguration(int targetSize, int overlapSize) {
        if (targetSize <= 0) {
            throw new IllegalArgumentException("chunking.target-size must be greater than 0, but was: " + targetSize);
        }
        if (overlapSize < 0) {
            throw new IllegalArgumentException("chunking.overlap-size must be greater than or equal to 0, but was: " + overlapSize);
        }
        if (targetSize <= overlapSize) {
            throw new IllegalArgumentException(String.format(
                    "chunking.target-size (%d) must be greater than chunking.overlap-size (%d)",
                    targetSize, overlapSize));
        }
    }

    /**
     * Splits extracted page-level text into deterministic, citation-preserving {@link DocumentChunk} entities.
     *
     * @param document parent document owning the chunks
     * @param pages    list of page text objects extracted from the PDF
     * @return list of ordered, indexed {@link DocumentChunk} records
     */
    public List<DocumentChunk> chunkDocument(Document document, List<PageText> pages) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (pages == null || pages.isEmpty()) {
            log.info("No pages provided for chunking document ID: {}", document != null ? document.getId() : "null");
            return chunks;
        }

        Long docId = document != null ? document.getId() : null;
        log.info("Document chunking started for document ID: {}, total pages: {}", docId, pages.size());

        int chunkIndexCounter = 0;

        for (PageText page : pages) {
            String pageText = page.text();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            int pageNumber = page.pageNumber();
            int textLength = pageText.length();

            // Rule 3: If page text length <= target size, create one chunk for the whole page
            if (textLength <= targetSize) {
                DocumentChunk chunk = new DocumentChunk(
                        document,
                        chunkIndexCounter++,
                        pageNumber,
                        pageText.trim(),
                        0,
                        textLength
                );
                chunks.add(chunk);
                continue;
            }

            // Rule 4: Sliding window chunking for pages longer than targetSize
            int start = 0;
            while (start < textLength) {
                int end = Math.min(start + targetSize, textLength);

                // Rule 5 & 6: If not at the end of the text, seek a natural boundary (whitespace / punctuation)
                if (end < textLength) {
                    int boundaryLimit = Math.max(start + (targetSize / 2), end - overlapSize);
                    int naturalBoundary = findNaturalBoundary(pageText, end, boundaryLimit);
                    if (naturalBoundary > start) {
                        end = naturalBoundary;
                    }
                }

                String chunkText = pageText.substring(start, end).trim();
                // Rule 8: Do not create empty chunks
                if (!chunkText.isEmpty()) {
                    DocumentChunk chunk = new DocumentChunk(
                            document,
                            chunkIndexCounter++,
                            pageNumber,
                            chunkText,
                            start,
                            end
                    );
                    chunks.add(chunk);
                }

                if (end >= textLength) {
                    break;
                }

                // Rule 7: Advance window by (targetSize - overlapSize), ensuring forward progress
                int nextStart = Math.max(start + 1, end - overlapSize);
                while (nextStart < textLength && Character.isWhitespace(pageText.charAt(nextStart))) {
                    nextStart++;
                }

                if (nextStart <= start) {
                    nextStart = start + 1;
                }
                start = nextStart;
            }
        }

        log.info("Document chunking completed for document ID: {}. Generated {} chunks", docId, chunks.size());
        return chunks;
    }

    /**
     * Looks backwards from {@code candidateEnd} down to {@code searchFloor} to find a natural boundary
     * (sentence punctuation or whitespace).
     */
    private int findNaturalBoundary(String text, int candidateEnd, int searchFloor) {
        // First priority: sentence-ending punctuation followed by whitespace (e.g. ". ", "! ", "? ")
        for (int i = candidateEnd; i >= searchFloor; i--) {
            char c = text.charAt(i);
            if (i > searchFloor && (c == ' ' || c == '\n') && isSentencePunctuation(text.charAt(i - 1))) {
                return i;
            }
        }

        // Second priority: standard whitespace or newline boundary
        for (int i = candidateEnd; i >= searchFloor; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        return -1;
    }

    private boolean isSentencePunctuation(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    public int getTargetSize() {
        return targetSize;
    }

    public int getOverlapSize() {
        return overlapSize;
    }
}
