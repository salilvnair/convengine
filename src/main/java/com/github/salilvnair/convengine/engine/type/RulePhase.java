package com.github.salilvnair.convengine.engine.type;

public enum RulePhase {
    POST_DIALOGUE_ACT,
    POST_SCHEMA_EXTRACTION,
    PRE_AGENT_MCP,
    PRE_RESPONSE_RESOLUTION,
    POST_CLASSIFIER_INTENT,
    POST_AGENT_INTENT,
    POST_AGENT_MCP,
    POST_SEMANTIC_INTERPRET,
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
        if ("CLASSIFIER_POST_INTENT".equals(normalized)
                || "POST_CLASSIFIER_INTENT".equals(normalized)) {
            return POST_CLASSIFIER_INTENT.name();
        }
        if ("AGENT_POST_MCP".equals(normalized)) {
            return POST_AGENT_MCP.name();
        }
        if ("AGENT_POST_SEMANTIC_INTERPRET".equals(normalized)
                || "SEMANTIC_INTERPRET_POST".equals(normalized)) {
            return POST_SEMANTIC_INTERPRET.name();
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
