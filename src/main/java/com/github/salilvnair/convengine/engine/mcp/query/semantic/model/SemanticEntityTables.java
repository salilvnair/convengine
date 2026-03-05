package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import java.util.List;

public record SemanticEntityTables(
        String primary,
        List<String> related
) {
    public SemanticEntityTables {
        related = related == null ? List.of() : List.copyOf(related);
    }
}
