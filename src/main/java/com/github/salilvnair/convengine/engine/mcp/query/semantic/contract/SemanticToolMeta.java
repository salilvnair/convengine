package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.List;

public record SemanticToolMeta(
        String tool,
        String version,
        Double confidence,
        Boolean needsClarification,
        String clarificationQuestion,
        List<SemanticAmbiguity> ambiguities,
        Boolean operationSupported,
        Boolean unsupported,
        String unsupportedMessage
) {
    public SemanticToolMeta {
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
        if (operationSupported == null) {
            operationSupported = !Boolean.TRUE.equals(unsupported);
        }
        if (Boolean.FALSE.equals(operationSupported)) {
            unsupported = true;
        }
        unsupported = Boolean.TRUE.equals(unsupported);
        if (!unsupported) {
            unsupportedMessage = null;
        }
    }
}
