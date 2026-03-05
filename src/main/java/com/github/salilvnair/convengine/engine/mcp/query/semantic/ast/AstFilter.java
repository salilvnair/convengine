package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

public record AstFilter(
        String field,
        String op,
        Object value
) {
}
