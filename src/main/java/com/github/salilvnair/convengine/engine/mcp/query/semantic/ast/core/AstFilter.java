package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

public record AstFilter(
        String field,
        String op,
        Object value
) {
    public AstOperator operatorEnum() {
        if (op == null || op.isBlank()) {
            return AstOperator.EQ;
        }
        String normalized = op.trim().toUpperCase().replace("-", "_");
        if ("CONTAINS".equals(normalized)) return AstOperator.ILIKE;
        if ("=".equals(normalized)) return AstOperator.EQ;
        if ("!=".equals(normalized) || "<>".equals(normalized)) return AstOperator.NE;
        if (">".equals(normalized)) return AstOperator.GT;
        if (">=".equals(normalized)) return AstOperator.GTE;
        if ("<".equals(normalized)) return AstOperator.LT;
        if ("<=".equals(normalized)) return AstOperator.LTE;
        return AstOperator.valueOf(normalized);
    }
}
