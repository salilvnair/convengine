package com.github.salilvnair.convengine.engine.type;

public enum RulePhase {
    PRE_RESPONSE_RESOLUTION,
    POST_AGENT_INTENT,
    POST_AGENT_MCP,
    POST_TOOL_EXECUTION;

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRE_RESPONSE_RESOLUTION.name();
        }
        String normalized = raw.trim().toUpperCase();
        if ("PIPELINE_RULES".equals(normalized)) {
            return PRE_RESPONSE_RESOLUTION.name();
        }
        if ("AGENT_POST_INTENT".equals(normalized)) {
            return POST_AGENT_INTENT.name();
        }
        if ("AGENT_POST_MCP".equals(normalized)) {
            return POST_AGENT_MCP.name();
        }
        if ("TOOL_POST_EXECUTION".equals(normalized)) {
            return POST_TOOL_EXECUTION.name();
        }
        for (RulePhase value : values()) {
            if (value.name().equals(normalized)) {
                return normalized;
            }
        }
        return PRE_RESPONSE_RESOLUTION.name();
    }
}
