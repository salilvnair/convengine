package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import java.util.List;

public record CanonicalIntent(
        String intent,
        String entity,
        String queryClass,
        List<SemanticFilter> filters,
        SemanticTimeRange timeRange,
        List<SemanticSort> sort,
        Integer limit
) {
    public CanonicalIntent {
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }
}
