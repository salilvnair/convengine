package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

public record SemanticTimeRange(
        String kind,
        String value,
        String timezone,
        String from,
        String to
) {
}
