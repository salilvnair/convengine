package com.github.salilvnair.convengine.engine.agent.query.semantic.model;

public record SemanticIntentFilter(
        String field,
        String op,
        Object value
) {
}

