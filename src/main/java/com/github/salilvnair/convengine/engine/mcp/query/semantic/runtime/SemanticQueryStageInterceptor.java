package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SemanticQueryStageInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeStage(String stage, EngineSession session, Object payload) {
    }

    default void afterStage(String stage, EngineSession session, Object payload) {
    }

    default void onError(String stage, EngineSession session, Exception ex) {
    }
}
