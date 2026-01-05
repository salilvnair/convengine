package com.github.salilvnair.convengine.llm.core;

public interface LlmClient {
    String generateText(String hint, String contextJson);
    String generateJson(String hint, String jsonSchema, String contextJson);
}
