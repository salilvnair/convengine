package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import java.util.List;

public record SemanticResolveResponse(
        SemanticToolMeta meta,
        ResolvedSemanticPlan resolvedPlan,
        List<SemanticUnresolvedItem> unresolved
) {
    public SemanticResolveResponse {
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }
}
