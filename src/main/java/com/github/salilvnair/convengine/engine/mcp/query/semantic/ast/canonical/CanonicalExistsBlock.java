package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

public record CanonicalExistsBlock(
        String entity,
        CanonicalFilterGroup where,
        boolean notExists
) {
}
