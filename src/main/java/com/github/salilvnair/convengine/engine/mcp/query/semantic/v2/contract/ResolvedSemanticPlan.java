package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import java.util.List;

public record ResolvedSemanticPlan(
        String queryClass,
        String baseEntity,
        String baseTable,
        List<ResolvedSelectItem> select,
        List<ResolvedFilter> filters,
        List<ResolvedJoinPlan> joins,
        ResolvedTimeRange timeRange,
        List<ResolvedSort> sort,
        Integer limit
) {
    public ResolvedSemanticPlan {
        select = select == null ? List.of() : List.copyOf(select);
        filters = filters == null ? List.of() : List.copyOf(filters);
        joins = joins == null ? List.of() : List.copyOf(joins);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }
}
