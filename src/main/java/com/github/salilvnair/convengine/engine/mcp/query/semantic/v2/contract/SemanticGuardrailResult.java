package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record SemanticGuardrailResult(
        Boolean allowed,
        String reason
) {
}
