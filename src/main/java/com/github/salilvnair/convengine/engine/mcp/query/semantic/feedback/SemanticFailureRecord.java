package com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback;

import java.util.Map;
import java.util.UUID;

public record SemanticFailureRecord(
        UUID conversationId,
        String question,
        String generatedSql,
        String correctSql,
        String rootCause,
        String reason,
        String stage,
        Map<String, Object> metadata
) {
}
