package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record SemanticFilter(
        String field,
        String op,
        Object value
) {
}
