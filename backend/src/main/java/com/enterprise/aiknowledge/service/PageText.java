package com.enterprise.aiknowledge.service;

/**
 * Record representing extracted text for a specific page of a PDF document.
 *
 * @param pageNumber 1-based index of the page in the document
 * @param text       normalized text content of this page
 */
public record PageText(
        int pageNumber,
        String text
) {}
