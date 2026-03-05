package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import java.util.List;

public record CanonicalSubquerySpec(
        String entity,
        String selectField,
        CanonicalFilterGroup where,
        List<String> groupBy,
        CanonicalFilterGroup having,
        int limit
) {
    public CanonicalSubquerySpec {
        where = where == null ? new CanonicalFilterGroup(null, List.of(), List.of()) : where;
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        having = having == null ? new CanonicalFilterGroup(null, List.of(), List.of()) : having;
        limit = limit <= 0 ? 1 : limit;
    }
}
