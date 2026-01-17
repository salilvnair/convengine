package com.github.salilvnair.convengine.intent;

public record IntentAgentResult(
        String intent,
        double confidence,
        boolean needsClarification,
        String clarificationQuestion,
        boolean clarificationResolved
) {}
