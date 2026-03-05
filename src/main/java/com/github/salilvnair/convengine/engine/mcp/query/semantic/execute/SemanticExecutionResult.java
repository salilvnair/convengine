package com.github.salilvnair.convengine.engine.mcp.query.semantic.execute;

import java.util.List;
import java.util.Map;

public record SemanticExecutionResult(
        int rowCount,
        List<Map<String, Object>> rows
) {
}
