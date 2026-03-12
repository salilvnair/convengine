package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record SemanticFilter(
        String field,
        String op,
        Object value
) {
}
