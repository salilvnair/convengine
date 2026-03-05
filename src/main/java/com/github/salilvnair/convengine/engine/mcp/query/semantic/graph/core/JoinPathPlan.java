package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core;

import java.util.List;

public record JoinPathPlan(
        String baseTable,
        List<SchemaEdge> edges,
        List<String> requiredTables,
        List<String> unresolvedTables,
        double confidence
) {
}
