package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

import java.util.Map;

public record SemanticResolveRequest(
        CanonicalIntent canonicalIntent,
        String conversationId,
        Map<String, Object> context
) {
}
