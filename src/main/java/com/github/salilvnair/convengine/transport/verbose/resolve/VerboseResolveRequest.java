package com.github.salilvnair.convengine.transport.verbose.resolve;

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
        boolean error,
        Map<String, Object> metadata
) {
}
