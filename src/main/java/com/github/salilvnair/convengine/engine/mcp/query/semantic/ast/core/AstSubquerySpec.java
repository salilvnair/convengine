package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AstSubquerySpec(
        String entity,
        @JsonProperty("select_field") String selectField,
        AstFilterGroup where,
        @JsonProperty("group_by") List<String> groupBy,
        AstFilterGroup having,
        Integer limit
) {
    public AstSubquerySpec {
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        limit = limit == null || limit <= 0 ? 1 : limit;
    }
}
