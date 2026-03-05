package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;

public record CanonicalSubqueryFilter(
        String field,
        AstOperator operator,
        CanonicalSubquerySpec subquery
) {
}
