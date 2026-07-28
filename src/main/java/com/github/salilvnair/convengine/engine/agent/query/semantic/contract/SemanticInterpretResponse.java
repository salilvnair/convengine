package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

import java.util.Map;

public record SemanticInterpretResponse(
        SemanticToolMeta meta,
        String question,
        CanonicalIntent canonicalIntent,
        Map<String, Object> trace
) {
}
