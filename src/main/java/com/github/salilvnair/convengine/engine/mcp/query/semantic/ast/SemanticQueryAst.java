package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticQueryAst(
        String entity,
        List<String> select,
        List<AstFilter> filters,
        @JsonProperty("time_range") AstTimeRange timeRange,
        @JsonProperty("group_by") List<String> groupBy,
        List<String> metrics,
        List<AstSort> sort,
        Integer limit
) {
    public SemanticQueryAst {
        select = select == null ? List.of() : List.copyOf(select);
        filters = filters == null ? List.of() : List.copyOf(filters);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }
}
