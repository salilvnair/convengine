package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import java.util.List;

public record CanonicalWindowSpec(
        String name,
        String function,
        List<String> partitionBy,
        List<CanonicalSort> orderBy
) {
    public CanonicalWindowSpec {
        partitionBy = partitionBy == null ? List.of() : List.copyOf(partitionBy);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }
}
