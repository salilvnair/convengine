package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

public record ResolvedJoinPlan(
        String leftTable,
        String rightTable,
        String joinType,
        String on
) {
}
