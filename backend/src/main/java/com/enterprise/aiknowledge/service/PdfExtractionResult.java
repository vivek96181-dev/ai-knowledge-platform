package com.enterprise.aiknowledge.service;

/**
 * Container record holding results of a PDF text extraction operation.
 *
 * @param extractedText normalized extracted plain text
 * @param pageCount     total number of pages in the PDF document
 */
public record PdfExtractionResult(
        String extractedText,
        int pageCount
) {}
