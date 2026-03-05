package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface SemanticAstGenerator {

    default boolean supports(EngineSession session) {
        return true;
    }

    AstGenerationResult generate(String question, RetrievalResult retrieval, JoinPathPlan joinPathPlan, EngineSession session);
}
