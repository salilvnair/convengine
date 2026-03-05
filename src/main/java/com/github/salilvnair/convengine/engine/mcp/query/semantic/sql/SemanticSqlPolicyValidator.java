package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;

public interface SemanticSqlPolicyValidator {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    void validate(CompiledSql compiledSql, SemanticQueryContext context);
}
