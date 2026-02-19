package com.github.salilvnair.convengine.engine.type;

public enum RulePhase {
    PIPELINE_RULES,
    AGENT_POST_INTENT;

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PIPELINE_RULES.name();
        }
        String normalized = raw.trim().toUpperCase();
        for (RulePhase value : values()) {
            if (value.name().equals(normalized)) {
                return normalized;
            }
        }
        return PIPELINE_RULES.name();
    }
}
