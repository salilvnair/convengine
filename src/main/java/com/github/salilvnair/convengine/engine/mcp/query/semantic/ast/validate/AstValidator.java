package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface AstValidator {

    default boolean supports(EngineSession session) {
        return true;
    }

    AstValidationResult validate(CanonicalAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session);
}
