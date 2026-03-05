package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SemanticRetrievalInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeRetrieve(String question, EngineSession session) {
    }

    default RetrievalResult afterRetrieve(RetrievalResult result, EngineSession session) {
        return result;
    }

    default void onError(String question, EngineSession session, Exception ex) {
    }
}
