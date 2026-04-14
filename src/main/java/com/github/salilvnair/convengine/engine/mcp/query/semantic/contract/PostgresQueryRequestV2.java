package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.Map;

public record PostgresQueryRequestV2(
        String sql,
        Map<String, Object> params,
        Boolean readOnly,
        Integer timeoutMs
) {
}
