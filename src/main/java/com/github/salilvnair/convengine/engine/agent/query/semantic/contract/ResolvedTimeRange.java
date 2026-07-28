package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

public record ResolvedTimeRange(
        String column,
        String from,
        String to,
        String timezone
) {
}
