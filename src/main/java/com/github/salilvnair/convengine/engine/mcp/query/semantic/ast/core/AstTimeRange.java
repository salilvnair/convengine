package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AstTimeRange(
        String field,
        @JsonProperty("from") String from,
        @JsonProperty("to") String to
) {
}
