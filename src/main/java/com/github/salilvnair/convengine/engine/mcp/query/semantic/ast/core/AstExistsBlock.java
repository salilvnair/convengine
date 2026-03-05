package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AstExistsBlock(
        String entity,
        AstFilterGroup where,
        @JsonProperty("not_exists") Boolean notExists
) {
}
