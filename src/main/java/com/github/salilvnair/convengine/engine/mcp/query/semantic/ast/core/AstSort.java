package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

public record AstSort(
        String field,
        String direction,
        String nulls
) {
    public AstSort(String field, String direction) {
        this(field, direction, null);
    }

    public SortDirection directionEnum() {
        if (direction == null || direction.isBlank()) {
            return SortDirection.ASC;
        }
        return SortDirection.valueOf(direction.trim().toUpperCase());
    }

    public NullsOrder nullsEnum() {
        if (nulls == null || nulls.isBlank()) {
            return null;
        }
        return NullsOrder.valueOf(nulls.trim().toUpperCase());
    }
}
