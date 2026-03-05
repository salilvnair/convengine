package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AstWindowSpec(
        String name,
        String function,
        @JsonProperty("partition_by") List<String> partitionBy,
        @JsonProperty("order_by") List<AstSort> orderBy
) {
    public AstWindowSpec {
        partitionBy = partitionBy == null ? List.of() : List.copyOf(partitionBy);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }
}
