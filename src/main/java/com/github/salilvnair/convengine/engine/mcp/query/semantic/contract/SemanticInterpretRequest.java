package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import java.util.Map;

public record SemanticInterpretRequest(
        String question,
        String conversationId,
        Map<String, Object> context,
        Map<String, Object> hints
) {
}
