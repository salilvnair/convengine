package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SemanticAstGenerationInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeGenerate(String question, RetrievalResult retrieval, JoinPathPlan joinPathPlan, EngineSession session) {
    }

    default AstGenerationResult afterGenerate(AstGenerationResult result, EngineSession session) {
        return result;
    }

    default void onError(String question, EngineSession session, Exception ex) {
    }
}
