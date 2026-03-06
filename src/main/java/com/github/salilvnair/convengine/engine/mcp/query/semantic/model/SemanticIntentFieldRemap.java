package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticIntentFieldRemap(
        @JsonProperty("from_field") String fromField,
        @JsonProperty("to_field") String toField,
        @JsonProperty("value_starts_with") List<String> valueStartsWith
) {
    public SemanticIntentFieldRemap {
        valueStartsWith = valueStartsWith == null ? List.of() : List.copyOf(valueStartsWith);
    }
}

