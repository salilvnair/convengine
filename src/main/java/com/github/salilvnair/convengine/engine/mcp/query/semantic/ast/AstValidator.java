package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstValidator {

    default boolean supports(EngineSession session) {
        return true;
    }

    AstValidationResult validate(SemanticQueryAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session);
}
