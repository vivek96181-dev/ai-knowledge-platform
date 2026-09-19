package com.enterprise.aiknowledge.evaluation;

import com.enterprise.aiknowledge.evaluation.model.EvaluationDataset;
import com.enterprise.aiknowledge.evaluation.runner.EvaluationDatasetLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EvaluationDatasetLoader Unit Tests")
class EvaluationDatasetLoaderTest {

    private EvaluationDatasetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new EvaluationDatasetLoader(new ObjectMapper());
    }

    @Test
    @DisplayName("loadDefaultDataset loads bundled rag-baseline-v1 dataset successfully")
    void loadDefaultDataset_success() {
        EvaluationDataset dataset = loader.loadDefaultDataset();

        assertNotNull(dataset);
        assertEquals("rag-baseline-v1", dataset.name());
        assertEquals("1.0.0", dataset.version());
        assertFalse(dataset.cases().isEmpty());
        assertEquals(7, dataset.cases().size());

        // Verify first case properties
        var first = dataset.cases().get(0);
        assertEquals("eval-leave-001", first.id());
        assertTrue(first.question().contains("annual leave"));
        assertEquals(2, first.expectedChunkIds().size());
        assertEquals(List.of(101L, 102L), first.expectedChunkIds());
        assertFalse(first.isUnanswerable());
    }

    @Test
    @DisplayName("loadFromJsonString parses custom valid JSON dataset")
    void loadFromJsonString_success() {
        String json = """
                {
                  "name": "custom-eval-v1",
                  "version": "2.0.0",
                  "cases": [
                    {
                      "id": "case-01",
                      "question": "Where is the office?",
                      "expectedChunkIds": [10, 20],
                      "isUnanswerable": false
                    }
                  ]
                }
                """;

        EvaluationDataset dataset = loader.loadFromJsonString(json);
        assertNotNull(dataset);
        assertEquals("custom-eval-v1", dataset.name());
        assertEquals("2.0.0", dataset.version());
        assertEquals(1, dataset.cases().size());
        assertEquals("case-01", dataset.cases().get(0).id());
    }

    @Test
    @DisplayName("loadFromClasspath throws on missing resource")
    void loadFromClasspath_missingResource() {
        assertThrows(IllegalArgumentException.class, () ->
                loader.loadFromClasspath("evaluation/non-existent-dataset.json"));
    }

    @Test
    @DisplayName("loadFromClasspath throws on blank path")
    void loadFromClasspath_blankPath() {
        assertThrows(IllegalArgumentException.class, () ->
                loader.loadFromClasspath("   "));
    }

    @Test
    @DisplayName("loadFromJsonString throws on malformed JSON")
    void loadFromJsonString_malformedJson() {
        assertThrows(IllegalArgumentException.class, () ->
                loader.loadFromJsonString("{ malformed json ..."));
    }

    @Test
    @DisplayName("loadFromJsonString throws on missing name or empty cases")
    void loadFromJsonString_validationFailures() {
        // Missing name
        String noName = """
                {
                  "cases": [
                    {"id": "c1", "question": "q1"}
                  ]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> loader.loadFromJsonString(noName));

        // Empty cases
        String emptyCases = """
                {
                  "name": "test-dataset",
                  "cases": []
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> loader.loadFromJsonString(emptyCases));

        // Missing case question
        String missingQuestion = """
                {
                  "name": "test-dataset",
                  "cases": [
                    {"id": "c1"}
                  ]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> loader.loadFromJsonString(missingQuestion));
    }
}
