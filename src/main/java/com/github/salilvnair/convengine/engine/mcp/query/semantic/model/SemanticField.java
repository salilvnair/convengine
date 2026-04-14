package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticField(
        String column,
        String type,
        String description,
        @JsonProperty("filterable") Boolean filterable,
        @JsonProperty("searchable") Boolean searchable,
        @JsonProperty("key") Boolean key,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("allowed_values") List<String> allowedValues
) {
    public SemanticField {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public SemanticField(
            String column,
            String type,
            String description,
            Boolean filterable,
            Boolean searchable,
            Boolean key
    ) {
        this(column, type, description, filterable, searchable, key, null, null);
    }

    public SemanticField(
            String column,
            String type,
            String description,
            Boolean filterable,
            Boolean searchable,
            Boolean key,
            List<String> aliases
    ) {
        this(column, type, description, filterable, searchable, key, aliases, null);
    }
}
