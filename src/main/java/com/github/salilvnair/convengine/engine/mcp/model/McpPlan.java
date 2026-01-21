package com.github.salilvnair.convengine.engine.mcp.model;

import java.util.Map;

public record McpPlan(
        String action,          // CALL_TOOL | ANSWER
        String tool_code,
        Map<String, Object> args,
        String answer
) {}
