package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval;

import com.github.salilvnair.convengine.engine.session.EngineSession;

import java.util.Map;

/**
 * Adapter SPI for vector similarity lookup.
 * Default implementation returns empty scores.
 */
public interface SemanticVectorSearchAdapter {

    default String adapterName() {
        return "default";
    }

    default boolean supports(EngineSession session) {
        return true;
    }

    default Map<String, Double> entityScores(EngineSession session, float[] queryVector) {
        return Map.of();
    }

    default Map<String, Double> tableScores(EngineSession session, float[] queryVector) {
        return Map.of();
    }
}
