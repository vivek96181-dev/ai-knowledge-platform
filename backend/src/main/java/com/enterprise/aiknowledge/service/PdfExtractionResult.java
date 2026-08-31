package com.enterprise.aiknowledge.service;

import java.util.List;

/**
 * Container record holding results of a PDF text extraction operation.
 *
 * @param extractedText normalized extracted full plain text across all pages
 * @param pageCount     total number of pages in the PDF document
 * @param pages         list of individual page text records preserving page numbers
 */
public record PdfExtractionResult(
        String extractedText,
        int pageCount,
        List<PageText> pages
) {
    /**
     * Backward-compatible convenience constructor.
     */
    public PdfExtractionResult(String extractedText, int pageCount) {
        this(extractedText, pageCount, List.of());
    }
}
