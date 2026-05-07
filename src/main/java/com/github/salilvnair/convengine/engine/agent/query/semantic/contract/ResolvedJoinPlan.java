package com.github.salilvnair.convengine.engine.agent.query.semantic.contract;

public record ResolvedJoinPlan(
        String leftTable,
        String rightTable,
        String joinType,
        String on
) {
}
