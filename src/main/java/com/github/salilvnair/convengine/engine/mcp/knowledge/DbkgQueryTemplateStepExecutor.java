package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
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

    @Override
    public boolean supports(String executorCode) {
        return DbkgConstants.EXECUTOR_QUERY_TEMPLATE.equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config, Map<String, Object> runtime) {
        Map<String, Object> template = support.findRowByKey(support.cfg().getQueryTemplateTable(), "query_code", templateCode).orElse(null);
        if (template == null) {
            return Map.of(
                    DbkgConstants.KEY_PLACEHOLDER_SKIPPED, false,
                    DbkgConstants.KEY_ROW_COUNT, 0,
                    DbkgConstants.KEY_ROWS, List.of(),
                    DbkgConstants.KEY_MESSAGE, DbkgConstants.MESSAGE_QUERY_TEMPLATE_NOT_FOUND_PREFIX + templateCode);
        }
        if (!support.truthy(template.get("enabled"))) {
            return Map.of(
                    DbkgConstants.KEY_PLACEHOLDER_SKIPPED, true,
                    DbkgConstants.KEY_ROW_COUNT, 0,
                    DbkgConstants.KEY_ROWS, List.of(),
                    DbkgConstants.KEY_QUERY_CODE, templateCode,
                    DbkgConstants.KEY_MESSAGE, DbkgConstants.MESSAGE_QUERY_TEMPLATE_DISABLED);
        }

        String sql = support.asText(template.get("sql_template"));
        sqlGuardrail.assertReadOnly(sql, "DBKG query template [" + templateCode + "]");
        Map<String, Object> params = support.resolveQueryParams(templateCode, runtime);
        if (!params.containsKey("limit")) {
            params.put("limit", support.parseInt(template.get("default_limit"), 100));
        }
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(sql, params);
        auditSqlExecution(stepCode, templateCode, runtime, sql, params, rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DbkgConstants.KEY_QUERY_CODE, templateCode);
        out.put(DbkgConstants.KEY_ROW_COUNT, rows.size());
        out.put(DbkgConstants.KEY_PARAMS, params);
        out.put(DbkgConstants.KEY_ROWS, rows);
        if (DbkgConstants.STEP_LOOKUP_REQUEST.equalsIgnoreCase(stepCode)) {
            runtime.put(DbkgConstants.KEY_REQUEST_ROW_COUNT, rows.size());
        }
        if (!rows.isEmpty()) {
            runtime.put(DbkgConstants.KEY_LAST_ROW_COUNT, rows.size());
            runtime.put(DbkgConstants.KEY_LAST_ROWS, rows);
        }
        return out;
    }

    private void auditSqlExecution(
            String stepCode,
            String templateCode,
            Map<String, Object> runtime,
            String sql,
            Map<String, Object> params,
            List<Map<String, Object>> rows) {
        UUID conversationId = conversationId(runtime == null ? null : runtime.get("conversationId"));
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step_code", stepCode);
        payload.put("query_code", templateCode);
        payload.put("sql", sql);
        payload.put("params", params);
        payload.put("row_count", rows == null ? 0 : rows.size());
        payload.put("rows", rows == null ? List.of() : rows);
        audit.audit(ConvEngineAuditStage.DBKG_QUERY_SQL_EXECUTION, conversationId, payload);
        EngineSession session = session(runtime == null ? null : runtime.get("session"));
        if (session != null) {
            verbosePublisher.publish(session, "DbkgQueryTemplateStepExecutor", "DBKG_QUERY_SQL_EXECUTION",
                    null, null, false, payload);
        }
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
}
