package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import java.util.Map;

public record CompiledSql(
        String sql,
        Map<String, Object> params
) {
}
