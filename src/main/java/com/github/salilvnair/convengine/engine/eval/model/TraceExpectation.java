package com.github.salilvnair.convengine.engine.eval.model;

public record TraceExpectation(
        int turnIndex,
        String expectedIntent,
        String expectedState
) {
}

