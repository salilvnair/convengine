package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.NullsOrder;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SortDirection;

public record CanonicalSort(
        String field,
        SortDirection direction,
        NullsOrder nulls
) {
}
