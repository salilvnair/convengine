package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstPlanner {

    default boolean supports(EngineSession session) {
        return true;
    }

    JoinPathPlan plan(RetrievalResult retrievalResult, EngineSession session);
}
