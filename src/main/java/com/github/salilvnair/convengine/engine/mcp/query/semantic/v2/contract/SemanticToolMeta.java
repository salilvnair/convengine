package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

import java.util.List;

public record SemanticToolMeta(
        String tool,
        String version,
        Double confidence,
        Boolean needsClarification,
        String clarificationQuestion,
        List<SemanticAmbiguity> ambiguities
) {
    public SemanticToolMeta {
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
    }
}
