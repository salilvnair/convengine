package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import java.util.List;

public record AstFilterGroup(
        String op,
        List<AstFilter> conditions,
        List<AstFilterGroup> groups
) {
    public AstFilterGroup {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public AstLogicalOperator logicalOperator() {
        if (op == null || op.isBlank()) {
            return AstLogicalOperator.AND;
        }
        return AstLogicalOperator.valueOf(op.trim().toUpperCase());
    }
}
