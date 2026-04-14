package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record SemanticGuardrailResult(
        Boolean allowed,
        String reason
) {
}
