package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

public record AstGenerationResult(
        SemanticQueryAst ast,
        String rawJson,
        boolean repaired
) {
}
