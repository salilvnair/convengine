package com.github.salilvnair.convengine.intent;

public record AllowedIntent(
        String code,
        String description,
        String llmHint,
        int priority
) {}
