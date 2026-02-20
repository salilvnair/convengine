package com.github.salilvnair.convengine.engine.eval.model;

public record TraceTurnResult(
        int turnIndex,
        String userText,
        String intent,
        String state,
        String contextJson
) {
}

