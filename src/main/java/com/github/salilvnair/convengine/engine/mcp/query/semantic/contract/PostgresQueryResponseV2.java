package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.List;
import java.util.Map;

public record PostgresQueryResponseV2(
        String tool,
        Boolean success,
        Integer rowCount,
        List<Map<String, Object>> rows,
        PostgresQueryMeta meta
) {
    public PostgresQueryResponseV2 {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
