package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;

public interface SqlGuardrail {
    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    void validate(CompiledSql compiledSql, SemanticQueryContext context);
}
