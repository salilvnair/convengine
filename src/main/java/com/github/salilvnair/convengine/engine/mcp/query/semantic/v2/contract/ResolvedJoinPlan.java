package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record ResolvedJoinPlan(
        String leftTable,
        String rightTable,
        String joinType,
        String on
) {
}
