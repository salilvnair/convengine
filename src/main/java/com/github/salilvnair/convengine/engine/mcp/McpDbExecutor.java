package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class McpDbExecutor {

    private final NamedParameterJdbcTemplate jdbc; // uses your main datasource
    private final McpSqlGuardrail sqlGuardrail;
    private final AuditService audit;
    private final VerboseMessagePublisher verbosePublisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public String execute(CeMcpDbTool tool, Map<String, Object> args, EngineSession session) {
        Map<String, Object> safeArgs = (args == null) ? Map.of() : args;

        // expand identifiers (${table}, ${column})
        String sql = McpSqlTemplate.expandIdentifiers(tool, safeArgs);
        sqlGuardrail.assertReadOnly(sql, "MCP DB tool [" + tool.getTool().getToolCode() + "]");

        // bind params (:value, :limit)
        Map<String, Object> params = new HashMap<>(safeArgs);

        // enforce limit
        if (!params.containsKey("limit")) {
            params.put("limit", Math.min(tool.getMaxRows(), 200));
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql, params);
            auditSqlExecution(tool, session, sql, params, rows, null);
        } catch (Exception e) {
            auditSqlExecution(tool, session, sql, params, List.of(), e);
            throw e;
        }

        try {
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize rows", e);
        }
    }

    private void auditSqlExecution(
            CeMcpDbTool tool,
            EngineSession session,
            String sql,
            Map<String, Object> params,
            List<Map<String, Object>> rows,
            Exception error) {
        if (session == null || session.getConversationId() == null || tool == null || tool.getTool() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", tool.getTool().getToolCode());
        payload.put("tool_group", tool.getTool().getToolGroup());
        payload.put("sql", sql);
        payload.put("params", params);
        payload.put("status", error == null ? "SUCCESS" : "ERROR");
        payload.put("error", error == null ? null : error.getClass().getName() + ": " + error.getMessage());
        if (error != null) {
            payload.putAll(buildErrorPayload(error));
        }
        payload.put("row_count", rows == null ? 0 : rows.size());
        payload.put("rows", rows == null ? List.of() : rows);
        audit.audit(ConvEngineAuditStage.MCP_DB_SQL_EXECUTION, session.getConversationId(), payload);
        verbosePublisher.publish(session, "McpDbExecutor", "MCP_DB_SQL_EXECUTION", null,
                tool.getTool().getToolCode(), false, payload);
    }

    private Map<String, Object> buildErrorPayload(Exception error) {
        Map<String, Object> details = new LinkedHashMap<>();
        Throwable root = rootCause(error);
        details.put("error_class", error.getClass().getName());
        details.put("error_message", error.getMessage());
        details.put("root_cause_class", root == null ? null : root.getClass().getName());
        details.put("root_cause_message", root == null ? null : root.getMessage());
        SQLException sqlError = sqlException(error);
        details.put("sql_state", sqlError == null ? null : sqlError.getSQLState());
        details.put("sql_error_code", sqlError == null ? null : sqlError.getErrorCode());
        return details;
    }

    private SQLException sqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        Throwable next = current == null ? null : current.getCause();
        while (next != null && next != current) {
            current = next;
            next = current.getCause();
        }
        return current;
    }
}
