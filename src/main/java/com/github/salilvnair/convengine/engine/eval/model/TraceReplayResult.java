package com.github.salilvnair.convengine.engine.eval.model;

import java.util.List;

public record TraceReplayResult(
        List<TraceTurnResult> turns,
        List<String> assertionFailures
) {
    public boolean passed() {
        return assertionFailures == null || assertionFailures.isEmpty();
    }
}

