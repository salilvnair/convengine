package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

public record SemanticIntentFilter(
        String field,
        String op,
        Object value
) {
}

