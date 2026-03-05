package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstNormalizer {

    default boolean supports(EngineSession session) {
        return true;
    }

    SemanticQueryAstV1 normalize(SemanticQueryAstV1 ast, SemanticModel model, String selectedEntity, EngineSession session);
}
