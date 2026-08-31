package com.enterprise.aiknowledge.service;

import com.enterprise.aiknowledge.model.Document;
import com.enterprise.aiknowledge.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests verifying {@link ChunkingService} chunking logic, boundary preservation, configuration validation,
 * and citation metadata.
 */
class ChunkingServiceTest {

    private ChunkingService chunkingService;
    private Document dummyDocument;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService(800, 150);
        dummyDocument = new Document();
    }

    @Test
    @DisplayName("Configuration validation rejects invalid targetSize and overlapSize")
    void configurationValidation() {
        assertThatThrownBy(() -> new ChunkingService(0, 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-size must be greater than 0");

        assertThatThrownBy(() -> new ChunkingService(800, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap-size must be greater than or equal to 0");

        assertThatThrownBy(() -> new ChunkingService(800, 800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-size (800) must be greater than chunking.overlap-size (800)");

        assertThatThrownBy(() -> new ChunkingService(500, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-size (500) must be greater than chunking.overlap-size (600)");
    }

    @Test
    @DisplayName("Short page text (<= targetSize) produces exactly 1 chunk with correct character offsets")
    void shortDocumentProducesSingleChunk() {
        String text = "This is a short paragraph of text for testing document chunking behavior.";
        List<PageText> pages = List.of(new PageText(1, text));

        List<DocumentChunk> chunks = chunkingService.chunkDocument(dummyDocument, pages);

        assertThat(chunks).hasSize(1);
        DocumentChunk chunk = chunks.getFirst();
        assertThat(chunk.getChunkIndex()).isEqualTo(0);
        assertThat(chunk.getPageNumber()).isEqualTo(1);
        assertThat(chunk.getText()).isEqualTo(text);
        assertThat(chunk.getCharacterStart()).isEqualTo(0);
        assertThat(chunk.getCharacterEnd()).isEqualTo(text.length());
    }

    @Test
    @DisplayName("Long page text (> targetSize) produces multiple overlapping chunks")
    void longDocumentProducesMultipleOverlappingChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("Sentence number ").append(i).append(" discussing Enterprise AI Knowledge Platform architectures and RAG pipelines. ");
        }
        String longText = sb.toString().trim();

        List<PageText> pages = List.of(new PageText(1, longText));

        List<DocumentChunk> chunks = chunkingService.chunkDocument(dummyDocument, pages);

        assertThat(chunks.size()).isGreaterThan(1);

        // Verify sequential indexing & character offsets
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            assertThat(chunk.getChunkIndex()).isEqualTo(i);
            assertThat(chunk.getPageNumber()).isEqualTo(1);
            assertThat(chunk.getText()).isNotBlank();
            assertThat(chunk.getCharacterStart()).isLessThan(chunk.getCharacterEnd());
        }

        // Verify overlap between chunk 0 and chunk 1
        assertThat(chunks.get(1).getCharacterStart()).isLessThan(chunks.get(0).getCharacterEnd());
    }

    @Test
    @DisplayName("Chunking preserves word and sentence boundaries without cutting words in half")
    void wordBoundariesArePreserved() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("KnowledgeManagementSystemSegment_").append(i).append(". ");
        }
        String text = sb.toString().trim();

        ChunkingService customChunker = new ChunkingService(200, 50);
        List<DocumentChunk> chunks = customChunker.chunkDocument(dummyDocument, List.of(new PageText(1, text)));

        assertThat(chunks).isNotEmpty();
        for (DocumentChunk chunk : chunks) {
            assertThat(chunk.getText()).doesNotEndWith(" ");
            assertThat(chunk.getText()).doesNotStartWith(" ");
        }
    }

    @Test
    @DisplayName("Multi-page document preserves page numbers and assigns continuous sequential chunk indexes")
    void pageNumbersArePreservedAccurately() {
        List<PageText> pages = List.of(
                new PageText(1, "Page 1 overview of architectural components."),
                new PageText(2, "Page 2 detailed breakdown of Kafka asynchronous events."),
                new PageText(3, "Page 3 deep dive into Apache PDFBox and citation metadata.")
        );

        List<DocumentChunk> chunks = chunkingService.chunkDocument(dummyDocument, pages);

        assertThat(chunks).hasSize(3);

        assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).getPageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).getText()).isEqualTo("Page 1 overview of architectural components.");

        assertThat(chunks.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(chunks.get(1).getPageNumber()).isEqualTo(2);
        assertThat(chunks.get(1).getText()).isEqualTo("Page 2 detailed breakdown of Kafka asynchronous events.");

        assertThat(chunks.get(2).getChunkIndex()).isEqualTo(2);
        assertThat(chunks.get(2).getPageNumber()).isEqualTo(3);
        assertThat(chunks.get(2).getText()).isEqualTo("Page 3 deep dive into Apache PDFBox and citation metadata.");
    }

    @Test
    @DisplayName("Empty or whitespace-only pages are skipped and produce no invalid chunks")
    void emptyOrBlankPagesProduceNoChunks() {
        List<PageText> pages = List.of(
                new PageText(1, "   \n\t  "),
                new PageText(2, ""),
                new PageText(3, "Valid page content on page three.")
        );

        List<DocumentChunk> chunks = chunkingService.chunkDocument(dummyDocument, pages);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getPageNumber()).isEqualTo(3);
        assertThat(chunks.getFirst().getChunkIndex()).isEqualTo(0);
        assertThat(chunks.getFirst().getText()).isEqualTo("Valid page content on page three.");
    }

    @Test
    @DisplayName("Chunking algorithm produces deterministic results for identical input")
    void deterministicChunkingForIdenticalInput() {
        String text = "Deterministic text chunking ensures that the same input always yields identical chunk indices and text content.";
        List<PageText> pages = List.of(new PageText(1, text));

        List<DocumentChunk> run1 = chunkingService.chunkDocument(dummyDocument, pages);
        List<DocumentChunk> run2 = chunkingService.chunkDocument(dummyDocument, pages);

        assertThat(run1).hasSize(run2.size());
        assertThat(run1.getFirst().getText()).isEqualTo(run2.getFirst().getText());
        assertThat(run1.getFirst().getCharacterStart()).isEqualTo(run2.getFirst().getCharacterStart());
        assertThat(run1.getFirst().getCharacterEnd()).isEqualTo(run2.getFirst().getCharacterEnd());
        assertThat(run1.getFirst().getChunkIndex()).isEqualTo(run2.getFirst().getChunkIndex());
    }
}
