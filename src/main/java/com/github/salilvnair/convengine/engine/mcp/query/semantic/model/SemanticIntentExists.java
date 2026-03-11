package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticIntentExists(
        String entity,
        @JsonProperty("not_exists") Boolean notExists,
        List<SemanticIntentFilter> where
) {
    public SemanticIntentExists {
        where = where == null ? List.of() : List.copyOf(where);
        notExists = notExists != null && notExists;
    }
}
