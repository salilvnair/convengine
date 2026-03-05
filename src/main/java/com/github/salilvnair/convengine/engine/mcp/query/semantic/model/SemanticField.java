package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SemanticField(
        String column,
        String type,
        String description,
        @JsonProperty("filterable") Boolean filterable,
        @JsonProperty("searchable") Boolean searchable,
        @JsonProperty("key") Boolean key
) {
}
