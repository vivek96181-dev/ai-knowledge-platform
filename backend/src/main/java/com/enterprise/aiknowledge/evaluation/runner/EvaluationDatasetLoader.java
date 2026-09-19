package com.enterprise.aiknowledge.evaluation.runner;

import com.enterprise.aiknowledge.evaluation.model.EvaluationCase;
import com.enterprise.aiknowledge.evaluation.model.EvaluationDataset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and validates version-controlled evaluation datasets from JSON resources.
 */
@Component
public class EvaluationDatasetLoader {

    public static final String DEFAULT_DATASET_PATH = "evaluation/rag-evaluation-dataset-v1.json";

    private final ObjectMapper objectMapper;

    public EvaluationDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public EvaluationDataset loadDefaultDataset() {
        return loadFromClasspath(DEFAULT_DATASET_PATH);
    }

    public EvaluationDataset loadFromClasspath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Resource path cannot be blank");
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Evaluation dataset resource not found on classpath: " + resourcePath);
            }
            return parseDataset(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read evaluation dataset from: " + resourcePath, e);
        }
    }

    public EvaluationDataset loadFromJsonString(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            throw new IllegalArgumentException("JSON content cannot be blank");
        }
        try {
            return parseJsonNode(objectMapper.readTree(jsonContent));
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed JSON evaluation dataset", e);
        }
    }

    public EvaluationDataset parseDataset(InputStream inputStream) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            return parseJsonNode(root);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse evaluation dataset JSON stream", e);
        }
    }

    private EvaluationDataset parseJsonNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Evaluation dataset root must be a JSON object");
        }

        JsonNode nameNode = root.get("name");
        if (nameNode == null || nameNode.asText().isBlank()) {
            throw new IllegalArgumentException("Evaluation dataset 'name' field is required");
        }
        String name = nameNode.asText();

        String version = root.has("version") ? root.get("version").asText() : "1.0.0";
        String description = root.has("description") ? root.get("description").asText() : "";

        JsonNode casesNode = root.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset 'cases' array must contain at least one case");
        }

        List<EvaluationCase> cases = new ArrayList<>();
        for (JsonNode caseNode : casesNode) {
            cases.add(parseCase(caseNode));
        }

        return new EvaluationDataset(name, version, description, cases);
    }

    private EvaluationCase parseCase(JsonNode node) {
        if (!node.has("id") || node.get("id").asText().isBlank()) {
            throw new IllegalArgumentException("EvaluationCase 'id' cannot be blank");
        }
        if (!node.has("question") || node.get("question").asText().isBlank()) {
            throw new IllegalArgumentException("EvaluationCase 'question' cannot be blank");
        }

        String id = node.get("id").asText();
        String question = node.get("question").asText();

        List<Long> expectedChunkIds = new ArrayList<>();
        if (node.has("expectedChunkIds") && node.get("expectedChunkIds").isArray()) {
            for (JsonNode idNode : node.get("expectedChunkIds")) {
                expectedChunkIds.add(idNode.asLong());
            }
        }

        List<Long> expectedDocumentIds = new ArrayList<>();
        if (node.has("expectedDocumentIds") && node.get("expectedDocumentIds").isArray()) {
            for (JsonNode docIdNode : node.get("expectedDocumentIds")) {
                expectedDocumentIds.add(docIdNode.asLong());
            }
        }

        String referenceAnswer = node.has("referenceAnswer") ? node.get("referenceAnswer").asText() : null;

        List<String> requiredConcepts = new ArrayList<>();
        if (node.has("requiredConcepts") && node.get("requiredConcepts").isArray()) {
            for (JsonNode conceptNode : node.get("requiredConcepts")) {
                requiredConcepts.add(conceptNode.asText());
            }
        }

        boolean isUnanswerable = node.has("isUnanswerable") && node.get("isUnanswerable").asBoolean();
        String userEmail = node.has("userEmail") ? node.get("userEmail").asText() : "eval_user@example.com";
        boolean isAdmin = node.has("isAdmin") && node.get("isAdmin").asBoolean();

        return new EvaluationCase(
                id,
                question,
                expectedChunkIds,
                expectedDocumentIds,
                referenceAnswer,
                requiredConcepts,
                isUnanswerable,
                userEmail,
                isAdmin
        );
    }
}
