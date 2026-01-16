package com.github.salilvnair.convengine.llm.core;

public interface LlmClient {
    String generateText(String hint, String contextJson);
    String generateJson(String hint, String jsonSchema, String contextJson);
    float[] generateEmbedding(String input);
    default String generateJsonStrict(String hint, String jsonSchema, String context) {
        // fallback for older / non-strict models
        return generateJson(hint, jsonSchema, context);
    }
}
