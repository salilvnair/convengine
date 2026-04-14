package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.preflight.DbSqlPreflightService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class McpDbExecutor {
    private static final Pattern FORBIDDEN_SQL = Pattern.compile("(?i)\\b(update|delete|insert|drop|alter|truncate|create)\\b");
    private static final String CFG_DB_SQL_PREFLIGHT_SYSTEM_PROMPT = "DB_SQL_PREFLIGHT_SYSTEM_PROMPT";
    private static final String CFG_DB_SQL_PREFLIGHT_USER_PROMPT = "DB_SQL_PREFLIGHT_USER_PROMPT";
    private static final String CFG_DB_SQL_PREFLIGHT_SCHEMA_JSON = "DB_SQL_PREFLIGHT_SCHEMA_JSON";
    private static final String DEFAULT_DB_SQL_PREFLIGHT_SYSTEM_PROMPT = """
            You are a DB SQL preflight repair assistant for ConvEngine MCP DB tools.
            Repair only read-only SELECT SQL.
            Use runtime schema metadata and semantic hints as source of truth.
            Never invent unknown table/column names.
            Keep named params unchanged when possible.
            Return SQL ONLY.
            """;
    private static final String DEFAULT_DB_SQL_PREFLIGHT_USER_PROMPT = """
            Original SQL:
            {{sql_before}}

            Params:
            {{params_json}}

            Runtime schema details:
            {{schema_json}}

            Semantic hints:
            {{semantic_json}}

            Execution error:
            {{error_message}}
            """;
    private static final String DEFAULT_DB_SQL_PREFLIGHT_SCHEMA_JSON = """
            {
              "type":"object",
              "required":["sql"],
              "properties":{
                "sql":{"type":"string"}
              },
              "additionalProperties":false
            }
            """;

    private final NamedParameterJdbcTemplate jdbc; // uses your main datasource
    private final DbSqlPreflightService preflightService;
    private final LlmClient llmClient;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final CeConfigResolver configResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public String execute(CeMcpDbTool tool, Map<String, Object> args, EngineSession session) {
        Map<String, Object> safeArgs = (args == null) ? Map.of() : args;

        // expand identifiers (${table}, ${column})
        String sql = McpSqlTemplate.expandIdentifiers(tool, safeArgs);

        // bind params (:value, :limit)
        Map<String, Object> params = new HashMap<>(safeArgs);

        // enforce limit
        if (!params.containsKey("limit")) {
            params.put("limit", Math.min(tool.getMaxRows(), 200));
        }

        int maxRetries = Math.max(0, mcpConfig.getDb().getPreflight().getSqlAutoRepairMaxRetries());
        boolean sqlAutoRepairEnabled = mcpConfig.getDb().getPreflight().isSqlAutoRepairEnabled();
        String currentSql = sql;
        Map<String, Object> currentParams = params;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String sqlBeforePreflight = currentSql;
            DbSqlPreflightService.PreflightResult preflight = preflightService.prepare(currentSql, currentParams);
            currentSql = preflight.sql();
            currentParams = new HashMap<>(preflight.params());
            DbSqlPreflightService.RepairContext repairContext = preflightService.buildRepairContext(currentSql);
            auditPreflight(session, tool, attempt, maxRetries, sqlAutoRepairEnabled, sqlBeforePreflight, currentSql, currentParams, repairContext);
            enforceReadOnlySql(currentSql);
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(currentSql, currentParams);
                return mapper.writeValueAsString(rows);
            } catch (Exception e) {
                if (!sqlAutoRepairEnabled || attempt >= maxRetries) {
                    throw e;
                }
                String repaired = repairSql(currentSql, currentParams, e, repairContext);
                auditPreflightRepair(session, tool, attempt, maxRetries, currentSql, repaired, e);
                if (repaired == null || repaired.isBlank() || repaired.equalsIgnoreCase(currentSql)) {
                    throw e;
                }
                currentSql = repaired;
            }
        }
        throw new IllegalStateException("Failed to execute SQL after repair loop");
    }

    private String repairSql(
            String sql,
            Map<String, Object> params,
            Exception error,
            DbSqlPreflightService.RepairContext repairContext) {
        String schemaJsonForPrompt = toJsonSafe(repairContext == null ? Map.of() : repairContext.schemaDetails());
        String semanticJson = toJsonSafe(repairContext == null ? Map.of() : repairContext.semanticHints());
        String paramsJson = toJsonSafe(params == null ? Map.of() : params);
        String systemPrompt = configResolver.resolveString(this, CFG_DB_SQL_PREFLIGHT_SYSTEM_PROMPT,
                DEFAULT_DB_SQL_PREFLIGHT_SYSTEM_PROMPT);
        String userPromptTemplate = configResolver.resolveString(this, CFG_DB_SQL_PREFLIGHT_USER_PROMPT,
                DEFAULT_DB_SQL_PREFLIGHT_USER_PROMPT);
        String schemaResponseJson = configResolver.resolveString(this, CFG_DB_SQL_PREFLIGHT_SCHEMA_JSON,
                DEFAULT_DB_SQL_PREFLIGHT_SCHEMA_JSON);
        String prompt = systemPrompt + "\n\n" + applyPromptVars(userPromptTemplate, Map.of(
                "sql_before", sql == null ? "" : sql,
                "params_json", paramsJson,
                "schema_json", schemaJsonForPrompt,
                "semantic_json", semanticJson,
                "error_message", String.valueOf(error == null ? "" : error.getMessage())
        ));
        String repaired = extractSqlFromJsonOrFallback(prompt, schemaResponseJson);
        return normalizeSql(repaired);
    }

    private String extractSqlFromJsonOrFallback(String prompt, String schemaJson) {
        try {
            String out = llmClient.generateJsonStrict(prompt, schemaJson, "{}");
            Map<?, ?> parsed = mapper.readValue(out, Map.class);
            Object sql = parsed.get("sql");
            if (sql != null && !String.valueOf(sql).isBlank()) {
                return String.valueOf(sql);
            }
        } catch (Exception ignored) {
            // fallback path below
        }
        return llmClient.generateText(prompt, "{}");
    }

    private String applyPromptVars(String template, Map<String, String> vars) {
        String out = template == null ? "" : template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String token = "{{" + e.getKey() + "}}";
            out = out.replace(token, e.getValue() == null ? "" : e.getValue());
        }
        return out;
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

    private String toJsonSafe(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void auditPreflight(
            EngineSession session,
            CeMcpDbTool tool,
            int attempt,
            int maxRetries,
            boolean sqlAutoRepairEnabled,
            String sqlBeforePreflight,
            String sqlAfterPreflight,
            Map<String, Object> params,
            DbSqlPreflightService.RepairContext context) {
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("attempt", attempt + 1);
        meta.put("max_retries", maxRetries);
        meta.put("sql_auto_repair_enabled", sqlAutoRepairEnabled);
        meta.put("tool_id", tool == null ? null : tool.getToolId());
        payload.put("_meta", meta);
        payload.put("sql_before", sqlBeforePreflight);
        payload.put("sql_after", sqlAfterPreflight);
        payload.put("params", params == null ? Map.of() : params);
        payload.put("schema_knowledge_used", context == null ? Map.of() : context.schemaDetails());
        payload.put("semantic_knowledge_used", context == null ? Map.of() : context.semanticHints());
        auditService.audit(ConvEngineAuditStage.MCP_DB_SQL_PREFLIGHT, session.getConversationId(), payload);
    }

    private void auditPreflightRepair(
            EngineSession session,
            CeMcpDbTool tool,
            int attempt,
            int maxRetries,
            String failedSql,
            String repairedSql,
            Exception error) {
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("attempt", attempt + 1);
        meta.put("max_retries", maxRetries);
        meta.put("tool_id", tool == null ? null : tool.getToolId());
        payload.put("_meta", meta);
        payload.put("failed_sql", failedSql);
        payload.put("repaired_sql", repairedSql);
        payload.put("error", String.valueOf(error == null ? "" : error.getMessage()));
        auditService.audit(ConvEngineAuditStage.MCP_DB_SQL_PREFLIGHT_REPAIR, session.getConversationId(), payload);
    }

    private void enforceReadOnlySql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (!normalized.toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("MCP DB executor allows only SELECT statements");
        }
        if (FORBIDDEN_SQL.matcher(normalized).find()) {
            throw new IllegalArgumentException("MCP DB executor blocked forbidden SQL statement");
        }
    }
}
