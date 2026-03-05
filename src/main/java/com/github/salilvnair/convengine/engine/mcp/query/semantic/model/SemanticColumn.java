package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SemanticColumn(
        String type,
        String description,
        @JsonProperty("primary_key") Boolean primaryKey,
        @JsonProperty("foreign_key") SemanticForeignKey foreignKey
) {
}
