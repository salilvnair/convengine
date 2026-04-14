package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record ResolvedTimeRange(
        String column,
        String from,
        String to,
        String timezone
) {
}
