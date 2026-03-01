package com.github.salilvnair.convengine.transport.verbose.resolve;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.Builder;

import java.util.Map;

@Builder
public record VerboseResolveRequest(
        String intent,
        String state,
        String stepName,
        String determinant,
        Long ruleId,
        String toolCode,
        EngineSession session,
        boolean error,
        Map<String, Object> metadata
) {
}
