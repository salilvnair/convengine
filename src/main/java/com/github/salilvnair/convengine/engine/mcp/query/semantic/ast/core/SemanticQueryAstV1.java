package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticQueryAstV1(
        @JsonProperty("astVersion") String astVersion,
        String entity,
        List<String> select,
        List<AstProjection> projections,
        List<AstFilter> filters,
        AstFilterGroup where,
        @JsonProperty("time_range") AstTimeRange timeRange,
        @JsonProperty("exists") List<AstExistsBlock> existsBlocks,
        @JsonProperty("subquery_filters") List<AstSubqueryFilter> subqueryFilters,
        @JsonProperty("group_by") List<String> groupBy,
        List<String> metrics,
        List<AstWindowSpec> windows,
        List<AstSort> sort,
        AstFilterGroup having,
        Integer limit,
        Integer offset,
        Boolean distinct,
        @JsonProperty("join_hints") List<AstJoinHint> joinHints
) {
    public SemanticQueryAstV1 {
        astVersion = astVersion == null || astVersion.isBlank() ? "v1" : astVersion.trim();
        select = select == null ? List.of() : List.copyOf(select);
        projections = projections == null ? List.of() : List.copyOf(projections);
        filters = filters == null ? List.of() : List.copyOf(filters);
        existsBlocks = existsBlocks == null ? List.of() : List.copyOf(existsBlocks);
        subqueryFilters = subqueryFilters == null ? List.of() : List.copyOf(subqueryFilters);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        windows = windows == null ? List.of() : List.copyOf(windows);
        sort = sort == null ? List.of() : List.copyOf(sort);
        joinHints = joinHints == null ? List.of() : List.copyOf(joinHints);
        distinct = distinct != null && distinct;
        offset = offset == null || offset < 0 ? 0 : offset;
    }
}
