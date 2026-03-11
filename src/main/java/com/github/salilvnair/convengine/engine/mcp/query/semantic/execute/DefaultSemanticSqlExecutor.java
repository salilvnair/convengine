package com.github.salilvnair.convengine.engine.mcp.query.semantic.execute;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultSemanticSqlExecutor implements SemanticSqlExecutor {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public SemanticExecutionResult execute(CompiledSql compiledSql, SemanticQueryContext context) {
        Map<String, Object> params = normalizeParams(compiledSql.params() == null ? Map.of() : compiledSql.params());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                compiledSql.sql(),
                params
        );
        return new SemanticExecutionResult(rows.size(), List.copyOf(rows));
    }

    private Map<String, Object> normalizeParams(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(input.size());
        input.forEach((k, v) -> out.put(k, normalizeValue(v)));
        return out;
    }

    private Object normalizeValue(Object value) {
        if (!(value instanceof String raw)) {
            return value;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return value;
        }
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        return value;
    }
}
