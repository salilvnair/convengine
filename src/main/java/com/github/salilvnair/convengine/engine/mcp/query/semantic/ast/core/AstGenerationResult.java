package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

public record AstGenerationResult(
        SemanticQueryAstV1 ast,
        String rawJson,
        boolean repaired,
        String astVersion
) {
    public AstGenerationResult(SemanticQueryAstV1 ast, String rawJson, boolean repaired) {
        this(ast, rawJson, repaired, ast == null ? "v1" : ast.astVersion());
    }

    public AstGenerationResult(SemanticQueryAst ast, String rawJson, boolean repaired) {
        this(ast == null ? null : ast.toV1(), rawJson, repaired, "v1");
    }
}
