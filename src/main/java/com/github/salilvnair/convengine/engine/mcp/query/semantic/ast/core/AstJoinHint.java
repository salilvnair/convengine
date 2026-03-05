package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

public record AstJoinHint(
        String leftField,
        String rightField,
        String joinType
) {
}
