package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.util.McpSqlAuditHelper;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DbkgQueryTemplateStepExecutor implements DbkgStepExecutor {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final DbkgSupportService support;
    private final McpSqlGuardrail sqlGuardrail;
    private final AuditService audit;
    private final VerboseMessagePublisher verbosePublisher;
    private final LlmClient llm;
    private final ConvEngineMcpConfig mcpConfig;

    @Override
    public boolean supports(String executorCode) {
        return DbkgConstants.EXECUTOR_QUERY_TEMPLATE.equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config,
            Map<String, Object> runtime) {
        Map<String, Object> template = support
                .findRowByKey(support.cfg().getQueryTemplateTable(), DbkgConstants.COL_QUERY_CODE, templateCode).orElse(null);
        if (template == null) {
            return Map.of(
                    DbkgConstants.KEY_PLACEHOLDER_SKIPPED, false,
                    DbkgConstants.KEY_ROW_COUNT, 0,
                    DbkgConstants.KEY_ROWS, List.of(),
                    DbkgConstants.KEY_MESSAGE, DbkgConstants.MESSAGE_QUERY_TEMPLATE_NOT_FOUND_PREFIX + templateCode);
        }
        if (!support.truthy(template.get(DbkgConstants.COL_ENABLED))) {
            return Map.of(
                    DbkgConstants.KEY_PLACEHOLDER_SKIPPED, true,
                    DbkgConstants.KEY_ROW_COUNT, 0,
                    DbkgConstants.KEY_ROWS, List.of(),
                    DbkgConstants.KEY_QUERY_CODE, templateCode,
                    DbkgConstants.KEY_MESSAGE, DbkgConstants.MESSAGE_QUERY_TEMPLATE_DISABLED);
        }

        String sql = support.asText(template.get(DbkgConstants.COL_SQL_TEMPLATE));
        Map<String, Object> params = support.resolveQueryParams(templateCode, runtime);
        if (!params.containsKey(DbkgConstants.KEY_LIMIT)) {
            params.put(DbkgConstants.KEY_LIMIT, support.parseInt(template.get(DbkgConstants.COL_DEFAULT_LIMIT), 100));
        }
        sql = refineSqlIfEnabled(stepCode, templateCode, sql, params, runtime);
        sqlGuardrail.assertReadOnly(sql, "DBKG query template [" + templateCode + "]");
        List<Map<String, Object>> rows;
        List<Map<String, Object>> normalizedRows;
        try {
            rows = namedParameterJdbcTemplate.queryForList(sql, params);
            normalizedRows = support.normalizeRowValues(rows);
            auditSqlExecution(stepCode, templateCode, runtime, sql, params, normalizedRows, null);
        } catch (Exception e) {
            auditSqlExecution(stepCode, templateCode, runtime, sql, params, List.of(), e);
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DbkgConstants.KEY_QUERY_CODE, templateCode);
        out.put(DbkgConstants.KEY_ROW_COUNT, normalizedRows.size());
        out.put(DbkgConstants.KEY_PARAMS, params);
        out.put(DbkgConstants.KEY_ROWS, normalizedRows);
        if (DbkgConstants.STEP_LOOKUP_REQUEST.equalsIgnoreCase(stepCode)) {
            runtime.put(DbkgConstants.KEY_REQUEST_ROW_COUNT, normalizedRows.size());
        }
        runtime.put(DbkgConstants.KEY_LAST_ROW_COUNT, normalizedRows.size());
        runtime.put(DbkgConstants.KEY_LAST_ROWS, normalizedRows);
        return out;
    }

    private void auditSqlExecution(
            String stepCode,
            String templateCode,
            Map<String, Object> runtime,
            String sql,
            Map<String, Object> params,
            List<Map<String, Object>> rows,
            Exception error) {

        UUID conversationId = conversationId(runtime == null ? null : runtime.get(DbkgConstants.KEY_CONVERSATION_ID));
        EngineSession session = session(runtime == null ? null : runtime.get(DbkgConstants.KEY_SESSION));

        Map<String, Object> basePayload = new LinkedHashMap<>();
        basePayload.put(DbkgConstants.KEY_STEP_CODE_SNAKE, stepCode);
        basePayload.put(DbkgConstants.COL_QUERY_CODE, templateCode);
        basePayload.put(DbkgConstants.KEY_TOOL_CODE, DbkgConstants.TOOL_CODE_DBKG_INVESTIGATE_EXECUTE); // Added for verbose routing

        McpSqlAuditHelper.auditSqlExecution(
                audit,
                verbosePublisher,
                session,
                conversationId,
                DbkgConstants.COMPONENT_DBKG_QUERY_TEMPLATE_STEP_EXECUTOR,
                ConvEngineAuditStage.DBKG_QUERY_SQL_EXECUTION,
                ConvEngineAuditStage.DBKG_QUERY_SQL_EXECUTION_ERROR,
                basePayload,
                sql,
                params,
                rows,
                error);
    }

    private UUID conversationId(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private EngineSession session(Object value) {
        if (value instanceof EngineSession session) {
            return session;
        }
        return null;
    }

    private String refineSqlIfEnabled(
            String stepCode,
            String templateCode,
            String sql,
            Map<String, Object> params,
            Map<String, Object> runtime) {
        ConvEngineMcpConfig.Db.KnowledgeGraph cfg = mcpConfig.getDb().getKnowledgeGraph();
        if (cfg == null || !cfg.isSqlRefinementEnabled() || sql == null || sql.isBlank()) {
            return sql;
        }
        EngineSession session = session(runtime == null ? null : runtime.get(DbkgConstants.KEY_SESSION));
        String dialect = cfg.getSqlDialect();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(DbkgConstants.KEY_STEP_CODE_SNAKE, stepCode);
        input.put(DbkgConstants.COL_QUERY_CODE, templateCode);
        input.put(DbkgConstants.KEY_DIALECT, dialect);
        input.put(DbkgConstants.KEY_SQL, sql);
        input.put(DbkgConstants.KEY_PARAMS, params);

        String systemPrompt = """
                You are a SQL refiner for ConvEngine DBKG query templates.
                Return SQL ONLY. No markdown. No explanation.

                Constraints:
                - Keep named parameters exactly as provided (do not add/remove/rename params).
                - Preserve SELECT-only semantics.
                - Ensure the SQL is valid for the target dialect.
                - Avoid patterns that break parameter typing (for example, `:param IS NULL` in Postgres).
                - Keep the same projection and ordering unless required for correctness.
                """;

        String userPrompt = """
                Dialect: %s
                SQL:
                %s
                Params:
                %s
                """.formatted(
                dialect == null ? "" : dialect,
                sql,
                JsonUtil.toJson(params));

        if (session != null) {
            verbosePublisher.publish(session, DbkgConstants.COMPONENT_DBKG_QUERY_TEMPLATE_STEP_EXECUTOR, DbkgConstants.EVENT_DBKG_QUERY_SQL_REFINE_LLM_INPUT,
                    null, null, false, input);
            LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());
        }

        String refined;
        try {
            refined = llm.generateText(session, systemPrompt + "\n\n" + userPrompt, JsonUtil.toJson(input));
        } catch (Exception e) {
            if (session != null) {
                verbosePublisher.publish(session, DbkgConstants.COMPONENT_DBKG_QUERY_TEMPLATE_STEP_EXECUTOR, DbkgConstants.EVENT_DBKG_QUERY_SQL_REFINE_LLM_ERROR,
                        null, null, true, Map.of(DbkgConstants.KEY_ERROR_MESSAGE, String.valueOf(e.getMessage())));
            }
            return sql;
        } finally {
            LlmInvocationContext.clear();
        }

        String normalized = normalizeSql(refined);
        if (normalized.isBlank()) {
            return sql;
        }
        if (session != null) {
            verbosePublisher.publish(session, DbkgConstants.COMPONENT_DBKG_QUERY_TEMPLATE_STEP_EXECUTOR, DbkgConstants.EVENT_DBKG_QUERY_SQL_REFINE_LLM_OUTPUT,
                    null, null, false, Map.of(DbkgConstants.KEY_SQL, normalized));
        }
        return normalized;
    }

    private String normalizeSql(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceAll("(?s)^```[a-zA-Z0-9]*\\n", "");
            normalized = normalized.replaceAll("(?s)\\n```$", "");
        }
        return normalized.trim();
    }
}
