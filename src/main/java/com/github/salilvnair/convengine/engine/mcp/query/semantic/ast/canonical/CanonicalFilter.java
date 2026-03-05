package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;

public record CanonicalFilter(
        String field,
        AstOperator operator,
        Object value
) {
}
