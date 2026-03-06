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
        @JsonProperty("aliases") List<String> aliases
) {
    public SemanticField(
            String column,
            String type,
            String description,
            Boolean filterable,
            Boolean searchable,
            Boolean key
    ) {
        this(column, type, description, filterable, searchable, key, null);
    }
}
