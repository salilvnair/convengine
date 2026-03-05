package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstPlanningInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforePlan(RetrievalResult retrievalResult, EngineSession session) {
    }

    default JoinPathPlan afterPlan(JoinPathPlan plan, EngineSession session) {
        return plan;
    }

    default void onError(RetrievalResult retrievalResult, EngineSession session, Exception ex) {
    }
}
