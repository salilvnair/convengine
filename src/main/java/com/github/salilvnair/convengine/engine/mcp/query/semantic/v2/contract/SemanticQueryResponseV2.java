package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;

public record SemanticQueryResponseV2(
        SemanticToolMeta meta,
        SemanticQueryAstV1 ast,
        SemanticCompiledSql compiledSql,
        SemanticGuardrailResult guardrail
) {
}
