package com.github.salilvnair.convengine.engine.mcp.query.semantic.execute;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.CompiledSql;

public interface SemanticSqlExecutor {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    SemanticExecutionResult execute(CompiledSql compiledSql, SemanticQueryContext context);
}
