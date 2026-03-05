package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface JoinPathResolver {

    default boolean supports(EngineSession session) {
        return true;
    }

    JoinPathPlan resolve(RetrievalResult retrieval, EngineSession session);
}
