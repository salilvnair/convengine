package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@Deprecated(forRemoval = false)
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

    public SemanticQueryAstV1 toV1() {
        return new SemanticQueryAstV1(
                "v1",
                entity,
                select,
                List.of(),
                filters,
                null,
                timeRange,
                List.of(),
                List.of(),
                groupBy,
                metrics,
                List.of(),
                sort,
                null,
                limit,
                0,
                false,
                List.of()
        );
    }
}
