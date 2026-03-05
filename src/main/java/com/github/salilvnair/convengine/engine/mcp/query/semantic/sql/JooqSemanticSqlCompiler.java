package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@Order(0)
@RequiredArgsConstructor
public class JooqSemanticSqlCompiler implements SemanticSqlCompiler {

    private final ConvEngineMcpConfig mcpConfig;
    private final DefaultSemanticSqlCompiler delegate;

    @Override
    public boolean supports(SemanticQueryContext context) {
        String compiler = mcpConfig.getDb() == null
                || mcpConfig.getDb().getSemantic() == null
                || mcpConfig.getDb().getSemantic().getSql() == null
                ? "default"
                : mcpConfig.getDb().getSemantic().getSql().getCompiler();
        return "jooq".equalsIgnoreCase(compiler);
    }

    @Override
    public CompiledSql compile(SemanticQueryContext context) {
        CompiledSql compiled = delegate.compile(context);
        if (compiled == null || compiled.sql() == null || compiled.sql().isBlank()) {
            return compiled;
        }
        try {
            DSLContext dsl = DSL.using(resolveDialect());
            Query query = dsl.parser().parseQuery(compiled.sql());
            String sql = query.getSQL(ParamType.NAMED);
            if (compiled.params() == null || compiled.params().isEmpty()) {
                return new CompiledSql(sql, Map.of());
            }
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Param<?>> entry : query.getParams().entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String key = entry.getKey();
                Object value = compiled.params().get(key);
                if (value == null && entry.getValue() != null) {
                    value = entry.getValue().getValue();
                }
                params.put(key, value);
            }
            if (params.isEmpty()) {
                params.putAll(compiled.params());
            }
            return new CompiledSql(sql, Map.copyOf(params));
        } catch (Exception ex) {
            // Fail open to the default compiler output if jOOQ rendering/parsing cannot handle a statement variant.
            return compiled;
        }
    }

    private SQLDialect resolveDialect() {
        String dialect = mcpConfig.getDb() == null
                || mcpConfig.getDb().getSemantic() == null
                || mcpConfig.getDb().getSemantic().getSqlDialect() == null
                ? "postgres"
                : mcpConfig.getDb().getSemantic().getSqlDialect();
        String normalized = dialect.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL" -> SQLDialect.POSTGRES;
            case "MYSQL" -> SQLDialect.MYSQL;
            case "MARIADB" -> SQLDialect.MARIADB;
            case "SQLITE" -> SQLDialect.SQLITE;
            case "H2" -> SQLDialect.H2;
            default -> SQLDialect.POSTGRES;
        };
    }
}
