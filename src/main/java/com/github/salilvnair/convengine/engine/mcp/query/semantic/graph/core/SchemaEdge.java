package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core;

public record SchemaEdge(
        String leftTable,
        String leftColumn,
        String rightTable,
        String rightColumn,
        String source,
        String joinType
) {
}
