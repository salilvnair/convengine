package com.github.salilvnair.convengine.engine.agent.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SemanticSettings(
        @JsonProperty("default_limit") Integer defaultLimit,
        String timezone,
        @JsonProperty("sql_dialect") String sqlDialect
) {
}

