package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.exception.InvalidFileException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Service responsible for extracting plain text content and page count from PDF files using Apache PDFBox.
 *
 * <p><strong>Architectural Rationale:</strong><br>
 * Apache PDFBox runs natively inside the JVM without external CLI tool installations, native library dependencies,
 * or paid cloud service requirements. It provides efficient text stripping and document layout parsing.</p>
 */
@Service
public class PdfTextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractionService.class);

    /**
     * Parses the PDF file at the specified path and extracts plain text along with page count.
     *
     * @param filePath absolute path to the PDF file on storage
     * @return {@link PdfExtractionResult} containing normalized text and page count
     * @throws InvalidFileException if the file is missing, unreadable, or not a valid PDF
     */
    public PdfExtractionResult extractText(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            log.error("Cannot extract text: PDF file does not exist at path: {}", filePath);
            throw new InvalidFileException("PDF file does not exist at path: " + filePath);
        }

        if (!Files.isReadable(filePath)) {
            log.error("Cannot extract text: PDF file is unreadable at path: {}", filePath);
            throw new InvalidFileException("PDF file is unreadable at path: " + filePath);
        }

        File file = filePath.toFile();

        try (PDDocument pdDocument = Loader.loadPDF(file)) {
            int pageCount = pdDocument.getNumberOfPages();
            log.info("Successfully loaded PDF from path: {}. Page count: {}", filePath, pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(pdDocument);

            String normalizedText = normalizeText(rawText);

            return new PdfExtractionResult(normalizedText, pageCount);
        } catch (IOException ex) {
            log.error("Failed to parse or extract text from PDF at path: {}", filePath, ex);
            throw new InvalidFileException("Failed to extract text from invalid or corrupt PDF at path: " + filePath);
        }
    }

    /**
     * Normalizes extracted PDF text:
     * <ul>
     *   <li>Converts CRLF line breaks to standard newlines (\n)</li>
     *   <li>Reduces 3 or more consecutive blank lines down to double newlines (\n\n)</li>
     *   <li>Trims leading and trailing whitespace</li>
     * </ul>
     *
     * @param rawText raw text stripped from PDF
     * @return clean normalized text string
     */
    private String normalizeText(String rawText) {
        if (rawText == null) {
            return "";
        }
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }
}
