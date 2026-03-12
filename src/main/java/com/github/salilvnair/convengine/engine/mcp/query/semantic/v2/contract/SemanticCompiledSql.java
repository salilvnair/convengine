package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import java.util.Map;

public record SemanticCompiledSql(
        String sql,
        Map<String, Object> params
) {
}
