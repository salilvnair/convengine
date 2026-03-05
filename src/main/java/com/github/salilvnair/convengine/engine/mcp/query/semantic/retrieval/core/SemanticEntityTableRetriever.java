package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SemanticEntityTableRetriever {

    default boolean supports(EngineSession session) {
        return true;
    }

    RetrievalResult retrieve(String question, EngineSession session);
}
