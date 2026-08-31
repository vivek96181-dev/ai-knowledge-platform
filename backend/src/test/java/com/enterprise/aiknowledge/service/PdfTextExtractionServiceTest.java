package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.exception.InvalidFileException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PdfTextExtractionService} verifying PDFBox extraction functionality.
 */
class PdfTextExtractionServiceTest {

    private final PdfTextExtractionService pdfTextExtractionService = new PdfTextExtractionService();

    @Test
    @DisplayName("Extracts text and page count accurately from a multi-page PDF")
    void extractsTextAndPageCountAccurately(@TempDir Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("sample_doc.pdf");
        createSamplePdf(pdfPath, "Enterprise AI Knowledge Platform", 3);

        PdfExtractionResult result = pdfTextExtractionService.extractText(pdfPath);

        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.extractedText()).contains("Enterprise AI Knowledge Platform - Page 1");
        assertThat(result.extractedText()).contains("Enterprise AI Knowledge Platform - Page 2");
        assertThat(result.extractedText()).contains("Enterprise AI Knowledge Platform - Page 3");
    }

    @Test
    @DisplayName("Throws InvalidFileException when PDF file does not exist")
    void throwsExceptionWhenFileDoesNotExist(@TempDir Path tempDir) {
        Path nonExistentPath = tempDir.resolve("non_existent.pdf");

        assertThatThrownBy(() -> pdfTextExtractionService.extractText(nonExistentPath))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("Throws InvalidFileException when PDF file is corrupt or invalid")
    void throwsExceptionWhenPdfIsCorrupt(@TempDir Path tempDir) throws IOException {
        Path corruptPdfPath = tempDir.resolve("corrupt.pdf");
        Files.write(corruptPdfPath, "This is not a real PDF document content!".getBytes());

        assertThatThrownBy(() -> pdfTextExtractionService.extractText(corruptPdfPath))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Failed to extract text from invalid or corrupt PDF");
    }

    private void createSamplePdf(Path destinationPath, String text, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                    contents.beginText();
                    contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contents.newLineAtOffset(100, 700);
                    contents.showText(text + " - Page " + i);
                    contents.endText();
                }
            }
            doc.save(destinationPath.toFile());
        }
    }
}
