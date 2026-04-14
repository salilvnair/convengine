package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticRules(
        @JsonProperty("allowed_tables") List<String> allowedTables,
        @JsonProperty("deny_operations") List<String> denyOperations,
        @JsonProperty("max_result_limit") Integer maxResultLimit
) {
    public SemanticRules {
        allowedTables = allowedTables == null ? List.of() : List.copyOf(allowedTables);
        denyOperations = denyOperations == null ? List.of() : List.copyOf(denyOperations);
    }
}

