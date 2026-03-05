package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph;

import java.util.List;
import java.util.Set;

/**
 * Adapter SPI for schema graph operations.
 */
public interface SchemaGraphEngine {

    default String adapterName() {
        return "default";
    }

    default boolean supports(String adapterKey) {
        return true;
    }

    void refreshGraph();

    List<SchemaEdge> shortestPath(String fromTable, String toTable, int maxHops);

    Set<String> neighbors(String table);
}
