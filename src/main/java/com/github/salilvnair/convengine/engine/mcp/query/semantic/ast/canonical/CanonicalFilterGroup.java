package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstLogicalOperator;

import java.util.List;

public record CanonicalFilterGroup(
        AstLogicalOperator op,
        List<CanonicalFilter> conditions,
        List<CanonicalFilterGroup> groups
) {
    public CanonicalFilterGroup {
        op = op == null ? AstLogicalOperator.AND : op;
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public boolean isEmpty() {
        return conditions.isEmpty() && groups.isEmpty();
    }
}
