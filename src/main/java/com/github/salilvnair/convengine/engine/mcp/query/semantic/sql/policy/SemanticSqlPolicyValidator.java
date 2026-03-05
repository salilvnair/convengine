package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;

public interface SemanticSqlPolicyValidator extends SqlGuardrail {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    void validate(CompiledSql compiledSql, SemanticQueryContext context);
}
