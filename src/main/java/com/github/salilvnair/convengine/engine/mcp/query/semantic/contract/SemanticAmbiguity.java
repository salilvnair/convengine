package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.List;

public record SemanticAmbiguity(
        String type,
        String code,
        String message,
        Boolean required,
        List<SemanticAmbiguityOption> options
) {
    public SemanticAmbiguity {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
