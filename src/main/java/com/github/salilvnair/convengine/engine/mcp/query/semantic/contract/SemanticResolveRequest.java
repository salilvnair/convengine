package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.Map;

public record SemanticResolveRequest(
        CanonicalIntent canonicalIntent,
        String conversationId,
        Map<String, Object> context
) {
}
