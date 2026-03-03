package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.util.McpSqlAuditHelper;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
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

        Map<String, Object> basePayload = new LinkedHashMap<>();
        basePayload.put("tool_code", tool.getTool().getToolCode());
        basePayload.put("tool_group", tool.getTool().getToolGroup());

        McpSqlAuditHelper.auditSqlExecution(
                audit,
                verbosePublisher,
                session,
                null,
                "McpDbExecutor",
                ConvEngineAuditStage.MCP_DB_SQL_EXECUTION,
                ConvEngineAuditStage.MCP_DB_SQL_EXECUTION,
                basePayload,
                sql,
                params,
                rows,
                error);
    }
}
