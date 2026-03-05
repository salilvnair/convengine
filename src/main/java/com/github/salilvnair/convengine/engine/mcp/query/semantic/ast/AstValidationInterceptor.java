package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstValidationInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeValidate(SemanticQueryAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session) {
    }

    default AstValidationResult afterValidate(AstValidationResult result, EngineSession session) {
        return result;
    }

    default void onError(SemanticQueryAst ast, EngineSession session, Exception ex) {
    }
}
