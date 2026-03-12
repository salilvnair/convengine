package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record SemanticTimeRange(
        String kind,
        String value,
        String timezone,
        String from,
        String to
) {
}
