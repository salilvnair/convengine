package com.github.salilvnair.convengine.intent;

public record IntentAgentResult(
        String intent,
        String state,
        double confidence,
        boolean needsClarification,
        String clarificationQuestion,
        boolean clarificationResolved
) {}
