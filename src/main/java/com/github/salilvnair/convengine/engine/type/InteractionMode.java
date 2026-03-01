package com.github.salilvnair.convengine.engine.type;

import java.util.Locale;
import java.util.Optional;

public enum InteractionMode {
    NORMAL,
    IDLE,
    COLLECT,
    CONFIRM,
    PROCESSING,
    FINAL,
    ERROR,
    DISAMBIGUATE,
    FOLLOW_UP,
    PENDING_ACTION,
    REVIEW;

    public static Optional<InteractionMode> fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InteractionMode.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
