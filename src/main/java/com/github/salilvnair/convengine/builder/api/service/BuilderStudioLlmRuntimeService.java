package com.github.salilvnair.convengine.builder.api.service;

import com.github.salilvnair.convengine.engine.session.EngineSession;

import java.util.Map;

/**
 * Optional runtime LLM provider service for Builder Studio.
 *
 * ConvEngine core exposes the interface and controller surface; a consumer
 * application such as convengine-demo provides the actual implementation that
 * knows which providers/models are available from its application config.
 */
public interface BuilderStudioLlmRuntimeService {

    Map<String, Object> availableProviders();

    Map<String, Object> changeProvider(String provider, String model, Double temperature);

    String generateText(String provider, String model, Double temperature,
                        EngineSession session, String hint, String contextJson);

    String generateJson(String provider, String model, Double temperature,
                        EngineSession session, String hint, String jsonSchema, String contextJson);

    default String generateJsonStrict(String provider, String model, Double temperature,
                                      EngineSession session, String hint, String jsonSchema, String contextJson) {
        return generateJson(provider, model, temperature, session, hint, jsonSchema, contextJson);
    }
}