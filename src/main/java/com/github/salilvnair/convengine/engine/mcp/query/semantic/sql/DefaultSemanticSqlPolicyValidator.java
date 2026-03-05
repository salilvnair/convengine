package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
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
