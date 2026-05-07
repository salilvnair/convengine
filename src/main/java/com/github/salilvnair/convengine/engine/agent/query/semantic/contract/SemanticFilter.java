package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

public record SemanticFilter(
        String field,
        String op,
        Object value
) {
}
