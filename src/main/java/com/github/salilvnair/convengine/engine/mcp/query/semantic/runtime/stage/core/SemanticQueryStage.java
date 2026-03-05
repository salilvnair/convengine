package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;

public interface SemanticQueryStage {

    default String stageCode() {
        return getClass().getSimpleName();
    }

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    void execute(SemanticQueryContext context);
}
