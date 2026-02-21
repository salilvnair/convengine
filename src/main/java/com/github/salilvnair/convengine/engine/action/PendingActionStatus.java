package com.github.salilvnair.convengine.engine.action;

public enum PendingActionStatus {
    OPEN,
    IN_PROGRESS,
    EXECUTED,
    REJECTED,
    EXPIRED;

    public static PendingActionStatus from(String raw, PendingActionStatus fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return PendingActionStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

