package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SchemaGraphInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeResolve(RetrievalResult retrieval, EngineSession session) {
    }

    default JoinPathPlan afterResolve(JoinPathPlan plan, EngineSession session) {
        return plan;
    }

    default void onError(RetrievalResult retrieval, EngineSession session, Exception ex) {
    }
}
