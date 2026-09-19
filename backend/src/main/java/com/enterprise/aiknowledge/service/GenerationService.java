package com.enterprise.aiknowledge.service;

/**
 * Abstraction for LLM text generation.
 * Decouples the RAG orchestrator from specific AI providers / SDKs.
 */
public interface GenerationService {

    /**
     * Generates a grounded response given system instructions, retrieved context, and the user's question.
     *
     * @param systemInstruction behavioral constraints and grounding guidelines for the model
     * @param context           bounded, structured text retrieved from enterprise documents
     * @param question          the natural-language question asked by the user
     * @return generated answer text
     */
    String generateAnswer(String systemInstruction, String context, String question);

    /**
     * Returns the name of the generation model being used.
     */
    String getModel();
}
