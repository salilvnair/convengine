package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

public record SemanticGuardrailResult(
        Boolean allowed,
        String reason
) {
}
