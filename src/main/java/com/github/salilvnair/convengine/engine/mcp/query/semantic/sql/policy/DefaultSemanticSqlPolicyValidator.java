package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.*;

import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultSemanticSqlPolicyValidator implements SemanticSqlPolicyValidator {

    private final McpSqlGuardrail sqlGuardrail;

    @Override
    public void validate(CompiledSql compiledSql, SemanticQueryContext context) {
        if (compiledSql == null || compiledSql.sql() == null || compiledSql.sql().isBlank()) {
            throw new IllegalStateException("Compiled SQL is empty for semantic query.");
        }
        sqlGuardrail.assertReadOnly(compiledSql.sql(), "semantic.query");
    }
}
