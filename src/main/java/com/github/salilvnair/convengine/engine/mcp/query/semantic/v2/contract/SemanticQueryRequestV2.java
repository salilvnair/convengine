package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract;

public record SemanticQueryRequestV2(
        ResolvedSemanticPlan resolvedPlan,
        Boolean strictMode,
        Boolean dryRun,
        String conversationId
) {
}
