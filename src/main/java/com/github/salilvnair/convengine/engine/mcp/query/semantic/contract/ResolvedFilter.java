package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record ResolvedFilter(
        String field,
        String column,
        String op,
        Object value
) {
}
