package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record SemanticQueryResponseV2(
        SemanticToolMeta meta,
        Object ast,
        SemanticCompiledSql compiledSql,
        SemanticGuardrailResult guardrail
) {
}
