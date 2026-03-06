package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

public record AstSubqueryFilter(
        String field,
        String op,
        AstSubquerySpec subquery
) {
    public AstOperator operatorEnum() {
        if (op == null || op.isBlank()) {
            return AstOperator.EQ;
        }
        String normalized = normalizeOperator(op);
        normalized = normalizeAggregateOperator(normalized);
        if ("CONTAINS".equals(normalized)) return AstOperator.ILIKE;
        if ("IS_NOT".equals(normalized) || "NOT_NULL".equals(normalized) || "ISNOTNULL".equals(normalized)) {
            return AstOperator.IS_NOT_NULL;
        }
        if ("IS".equals(normalized) || "NULL".equals(normalized) || "ISNULL".equals(normalized)) {
            return AstOperator.IS_NULL;
        }
        if ("=".equals(normalized)) return AstOperator.EQ;
        if ("!=".equals(normalized) || "<>".equals(normalized)) return AstOperator.NE;
        if (">".equals(normalized)) return AstOperator.GT;
        if (">=".equals(normalized)) return AstOperator.GTE;
        if ("<".equals(normalized)) return AstOperator.LT;
        if ("<=".equals(normalized)) return AstOperator.LTE;
        return AstOperator.valueOf(normalized);
    }

    private String normalizeAggregateOperator(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        String[] candidates = {
                "IS_NOT_NULL", "IS_NULL", "NOT_IN",
                "BETWEEN", "ILIKE", "LIKE",
                "GTE", "LTE", "GT", "LT",
                "EQ", "NE", "IN"
        };
        for (String candidate : candidates) {
            if (normalized.equals(candidate) || normalized.endsWith("_" + candidate)) {
                return candidate;
            }
        }
        if (normalized.endsWith("_NOTNULL")) {
            return "IS_NOT_NULL";
        }
        if (normalized.endsWith("_NULL")) {
            return "IS_NULL";
        }
        return normalized;
    }

    private String normalizeOperator(String raw) {
        return raw == null
                ? ""
                : raw.trim()
                .toUpperCase()
                .replace("-", "_")
                .replace(' ', '_');
    }
}
