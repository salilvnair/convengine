package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstNormalizeInterceptor {

    default boolean supports(EngineSession session) {
        return true;
    }

    default void beforeNormalize(SemanticQueryAstV1 ast, SemanticModel model, String selectedEntity, EngineSession session) {
    }

    default SemanticQueryAstV1 afterNormalize(SemanticQueryAstV1 ast, EngineSession session) {
        return ast;
    }

    default void onError(SemanticQueryAstV1 ast, EngineSession session, Exception ex) {
    }
}
