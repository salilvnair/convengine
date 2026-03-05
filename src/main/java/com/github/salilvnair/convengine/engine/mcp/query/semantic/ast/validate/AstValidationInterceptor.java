package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;

public interface AstValidationInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeValidate(CanonicalAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session) {
    }

    default AstValidationResult afterValidate(AstValidationResult result, EngineSession session) {
        return result;
    }

    default void onError(CanonicalAst ast, EngineSession session, Exception ex) {
    }
}
