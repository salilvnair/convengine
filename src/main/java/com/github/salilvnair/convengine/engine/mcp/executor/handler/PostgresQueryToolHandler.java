package com.github.salilvnair.convengine.engine.mcp.executor.handler;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.interceptor.PostgresQueryInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback.SemanticFailureRecord;
import com.github.salilvnair.convengine.engine.mcp.util.McpSqlAuditHelper;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresQueryToolHandler implements DbToolHandler {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final McpSqlGuardrail sqlGuardrail;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final ObjectProvider<List<PostgresQueryInterceptor>> interceptorProvider;
    private final SemanticFailureFeedbackService failureFeedbackService;

    @Override
    public String toolCode() {
        return "postgres.query";
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        if (args == null) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        Object rawQuery = args.get("query");
        if ((rawQuery == null || String.valueOf(rawQuery).isBlank()) && args.containsKey("sql")) {
            rawQuery = args.get("sql");
            args.put("query", rawQuery);
        }
        if (rawQuery == null || String.valueOf(rawQuery).isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        String sql = String.valueOf(rawQuery).trim();
        if (sql.isBlank()) {
            throw new IllegalArgumentException("The provided SQL query is empty.");
        }
        String originalSql = sql;
        sql = applyInterceptors(sql, tool, args, session);
        args.put("query", sql);
        Map<String, Object> queryParams = resolveQueryParams(args);

        // 1. Assert read-only safety using the shared guardrail validation.
        sqlGuardrail.assertReadOnly(sql, "postgres.query tool");

        log.debug("Executing dynamic SQL from MCP: {}", sql);

        List<Map<String, Object>> rows = null;
        Exception executionError = null;
        Map<String, Object> resultPayload = new LinkedHashMap<>();

        // 2. Execute the read-only query
        try {
            rows = jdbcTemplate.queryForList(sql, queryParams);
            String semanticQuestion = semanticQuestion(session);

            resultPayload.put("status", "SUCCESS");
            resultPayload.put("rowCount", rows.size());
            Map<String, Object> correctionMeta = new LinkedHashMap<>();
            correctionMeta.put("toolCode", toolCode());
            correctionMeta.put("params", queryParams);
            correctionMeta.put("standalone_query", session == null ? null : session.getStandaloneQuery());
            failureFeedbackService.recordCorrection(
                    session == null ? null : session.getConversationId(),
                    semanticQuestion,
                    sql,
                    correctionMeta
            );

            // Limit the result set size returned to the LLM to prevent context bloat
            if (rows.size() > 100) {
                log.warn("Query returned {} rows, truncating to 100 for LLM context safety", rows.size());
                resultPayload.put("warning", "Result set truncated from " + rows.size() + " to 100 rows.");
                rows = rows.subList(0, 100);
            }
            resultPayload.put("rows", rows);

        } catch (Exception e) {
            executionError = e;
            log.error("Failed to execute dynamic LLM SQL query", e);
            resultPayload.put("status", "ERROR");
            resultPayload.put("error", e.getClass().getName() + ": " + e.getMessage());
            String semanticQuestion = semanticQuestion(session);
            failureFeedbackService.recordFailure(new SemanticFailureRecord(
                    session == null ? null : session.getConversationId(),
                    semanticQuestion,
                    sql,
                    null,
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    "POSTGRES_QUERY_EXECUTION",
                    Map.of(
                            "toolCode", toolCode(),
                            "params", queryParams
                    )
            ));
        }

        // 3. Audit & Verbose
        Map<String, Object> basePayload = new LinkedHashMap<>();
        basePayload.put("tool_code", toolCode());
        basePayload.put("args", args);
        if (!originalSql.equals(sql)) {
            basePayload.put("normalized_sql", sql);
        }
        if (resultPayload.containsKey("warning")) {
            basePayload.put("warning", resultPayload.get("warning"));
        }

        McpSqlAuditHelper.auditSqlExecution(
                auditService,
                verbosePublisher,
                session,
                null,
                "PostgresQueryToolHandler",
                ConvEngineAuditStage.DYNAMIC_SQL_EXECUTION,
                ConvEngineAuditStage.DYNAMIC_SQL_EXECUTION_ERROR,
                basePayload,
                sql,
                queryParams,
                rows,
                executionError);

        if (executionError != null) {
            throw new IllegalArgumentException("SQL execution failed: " + executionError.getMessage(), executionError);
        }

        return resultPayload;
    }

    private String semanticQuestion(EngineSession session) {
        if (session == null) {
            return null;
        }
        String standalone = session.getStandaloneQuery();
        if (standalone != null && !standalone.isBlank()) {
            return standalone.trim();
        }
        String userText = session.getUserText();
        return userText == null || userText.isBlank() ? null : userText.trim();
    }

    private String applyInterceptors(String sql, CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        List<PostgresQueryInterceptor> interceptors = interceptorProvider.getIfAvailable(List::of);
        if (interceptors == null || interceptors.isEmpty()) {
            return sql;
        }
        String current = sql;
        for (PostgresQueryInterceptor interceptor : interceptors) {
            if (interceptor == null) {
                continue;
            }
            if (!interceptor.supports(tool, session, args == null ? Map.of() : args)) {
                continue;
            }
            String next = interceptor.intercept(current, tool, session, args == null ? Map.of() : args);
            if (next != null && !next.isBlank()) {
                current = next;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveQueryParams(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Object raw = args.get("params");
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), coerceQueryParam(v)));
        return out;
    }

    private Object coerceQueryParam(Object value) {
        if (!(value instanceof String raw)) {
            return value;
        }
        String text = raw.trim();
        if (text.isBlank()) {
            return value;
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return Timestamp.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(text));
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return Date.valueOf(LocalDate.parse(text));
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }
}
