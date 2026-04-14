package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticJoinHint(
        @JsonProperty("commonly_joined_with") List<String> commonlyJoinedWith
) {
    public SemanticJoinHint {
        commonlyJoinedWith = commonlyJoinedWith == null ? List.of() : List.copyOf(commonlyJoinedWith);
    }
}

