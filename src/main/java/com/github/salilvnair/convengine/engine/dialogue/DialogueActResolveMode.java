package com.github.salilvnair.convengine.engine.dialogue;

public enum DialogueActResolveMode {
    REGEX_THEN_LLM,
    REGEX_ONLY,
    LLM_ONLY;

    public static DialogueActResolveMode from(String raw, DialogueActResolveMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase();
        for (DialogueActResolveMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return fallback;
    }
}
