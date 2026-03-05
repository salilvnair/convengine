package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;

public interface SemanticSqlCompiler {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    CompiledSql compile(SemanticQueryContext context);
}
