package com.github.salilvnair.convengine.engine.mcp.query.semantic.execute;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.CompiledSql;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultSemanticSqlExecutor implements SemanticSqlExecutor {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public SemanticExecutionResult execute(CompiledSql compiledSql, SemanticQueryContext context) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                compiledSql.sql(),
                compiledSql.params() == null ? Map.of() : compiledSql.params()
        );
        return new SemanticExecutionResult(rows.size(), List.copyOf(rows));
    }
}
