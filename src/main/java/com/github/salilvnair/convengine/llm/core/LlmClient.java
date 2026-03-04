package com.github.salilvnair.convengine.llm.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface LlmClient {
    String generateText(EngineSession session, String hint, String contextJson);
    String generateJson(EngineSession session, String hint, String jsonSchema, String contextJson);
    float[] generateEmbedding(EngineSession session, String input);
    default String generateJsonStrict(EngineSession session, String hint, String jsonSchema, String context) {
        // fallback for older / non-strict models
        return generateJson(session, hint, jsonSchema, context);
    }
}
