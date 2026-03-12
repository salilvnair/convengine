package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record ResolvedFilter(
        String field,
        String column,
        String op,
        Object value
) {
}
