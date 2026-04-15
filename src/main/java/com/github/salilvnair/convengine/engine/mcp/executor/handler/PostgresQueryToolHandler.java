package com.github.salilvnair.convengine.engine.mcp.executor.handler;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.preflight.DbSqlPreflightService;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.interceptor.PostgresQueryInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback.SemanticFailureRecord;
import com.github.salilvnair.convengine.engine.mcp.util.McpSqlAuditHelper;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PostgresQueryToolHandler implements DbToolHandler {
    private static final String AUDIT_SQL_REPAIR_LLM_INPUT = "DYNAMIC_SQL_REPAIR_LLM_INPUT";
    private static final String AUDIT_SQL_REPAIR_LLM_OUTPUT = "DYNAMIC_SQL_REPAIR_LLM_OUTPUT";
    private static final String AUDIT_SQL_REPAIR_LLM_ERROR = "DYNAMIC_SQL_REPAIR_LLM_ERROR";
    private static final String AUDIT_SQL_RECONCILE_INPUT = "DYNAMIC_SQL_RECONCILE_LLM_INPUT";
    private static final String AUDIT_SQL_RECONCILE_OUTPUT = "DYNAMIC_SQL_RECONCILE_LLM_OUTPUT";
    private static final String AUDIT_SQL_RECONCILE_ERROR = "DYNAMIC_SQL_RECONCILE_LLM_ERROR";
    private static final String AUDIT_SQL_RECONCILE_DIFF = "DYNAMIC_SQL_RECONCILE_DIFF";
    private static final String AUDIT_SQL_RECONCILE_FINAL = "DYNAMIC_SQL_RECONCILE_FINAL";
    private static final String CFG_DB_SQL_PREFLIGHT_SYSTEM_PROMPT = "DB_SQL_PREFLIGHT_SYSTEM_PROMPT";
    private static final String CFG_DB_SQL_PREFLIGHT_USER_PROMPT = "DB_SQL_PREFLIGHT_USER_PROMPT";
    private static final String CFG_DB_SQL_PREFLIGHT_SCHEMA_JSON = "DB_SQL_PREFLIGHT_SCHEMA_JSON";
    private static final String CFG_DB_SQL_RECONCILE_SYSTEM_PROMPT = "DB_SQL_RECONCILE_SYSTEM_PROMPT";
    private static final String CFG_DB_SQL_RECONCILE_USER_PROMPT = "DB_SQL_RECONCILE_USER_PROMPT";
    private static final String CFG_DB_SQL_RECONCILE_SCHEMA_JSON = "DB_SQL_RECONCILE_SCHEMA_JSON";
    private static final Pattern FROM_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z0-9_.]+)(?:\\s+([a-zA-Z0-9_]+))?");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)\\bjoin\\s+([a-zA-Z0-9_.]+)(?:\\s+([a-zA-Z0-9_]+))?");
    private static final Pattern PARAM_EQUALITY_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*:([a-zA-Z_][a-zA-Z0-9_]*)\\b");
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

            LLM Context JSON:
            {{context_json}}

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
    private static final String DEFAULT_DB_SQL_RECONCILE_SYSTEM_PROMPT = """
            You are a DB SQL schema/type reconciliation assistant for ConvEngine MCP DB tools.
            Validate SQL against provided semantic metadata and runtime DB schema.
            Focus on type-safe predicates and parameter compatibility.
            Keep query semantics unchanged.
            Never invent table/column names.
            For numeric columns, prefer CAST(:param AS <numeric-type>) when ambiguity can happen.
            For transition-log outputs, deduplicate to one row per (request_id, scenario_id) using DISTINCT ON.
            Use deterministic ordering with request_id, scenario_id, to_logged_at DESC, to_log_id DESC.
            Preserve named params and return JSON only.
            """;
    private static final String DEFAULT_DB_SQL_RECONCILE_USER_PROMPT = """
            Candidate SQL:
            {{sql_before}}

            Params:
            {{params_json}}

            Preflight diagnostics:
            {{preflight_json}}

            Runtime schema details:
            {{schema_json}}

            Semantic hints:
            {{semantic_json}}

            Task:
            - Fix type mismatches and unsafe comparisons.
            - Keep joins/filters semantics unchanged.
            - Enforce dedup for transition logs: one row per (request_id, scenario_id) via DISTINCT ON + deterministic ordering.
            - Keep params unchanged.
            - Return SQL only.
            """;
    private static final String DEFAULT_DB_SQL_RECONCILE_SCHEMA_JSON = """
            {
              "type":"object",
              "required":["sql"],
              "properties":{
                "sql":{"type":"string"}
              },
              "additionalProperties":false
            }
            """;
    private static final String TOOL_EXECUTION_FAILED = "Tool execution failed";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final McpSqlGuardrail sqlGuardrail;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final ObjectProvider<List<PostgresQueryInterceptor>> interceptorProvider;
    private final SemanticFailureFeedbackService failureFeedbackService;
    private final DbSqlPreflightService preflightService;
    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<LlmClient> llmClientProvider;
    private final CeConfigResolver configResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public PostgresQueryToolHandler(
            NamedParameterJdbcTemplate jdbcTemplate,
            McpSqlGuardrail sqlGuardrail,
            AuditService auditService,
            VerboseMessagePublisher verbosePublisher,
            ObjectProvider<List<PostgresQueryInterceptor>> interceptorProvider,
            SemanticFailureFeedbackService failureFeedbackService,
            DbSqlPreflightService preflightService,
            ConvEngineMcpConfig mcpConfig,
            ObjectProvider<LlmClient> llmClientProvider,
            CeConfigResolver configResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlGuardrail = sqlGuardrail;
        this.auditService = auditService;
        this.verbosePublisher = verbosePublisher;
        this.interceptorProvider = interceptorProvider;
        this.failureFeedbackService = failureFeedbackService;
        this.preflightService = preflightService;
        this.mcpConfig = mcpConfig;
        this.llmClientProvider = llmClientProvider;
        this.configResolver = configResolver;
    }

    public PostgresQueryToolHandler(
            NamedParameterJdbcTemplate jdbcTemplate,
            McpSqlGuardrail sqlGuardrail,
            AuditService auditService,
            VerboseMessagePublisher verbosePublisher,
            ObjectProvider<List<PostgresQueryInterceptor>> interceptorProvider,
            SemanticFailureFeedbackService failureFeedbackService) {
        this(jdbcTemplate, sqlGuardrail, auditService, verbosePublisher, interceptorProvider, failureFeedbackService, null, null, null, null);
    }

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
        Map<String, Object> baseParams = resolveQueryParams(args);
        String semanticQuestion = semanticQuestion(session);
        int maxRetries = maxSqlAutoRepairRetries();
        boolean autoRepairEnabled = isSqlAutoRepairEnabled() && llmClientProvider != null && llmClientProvider.getIfAvailable() != null;

        String currentSql = sql;
        Map<String, Object> currentParams = new LinkedHashMap<>(baseParams);
        Exception lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            sqlGuardrail.assertReadOnly(currentSql, "postgres.query tool");
            String sqlBeforePreflight = currentSql;
            Map<String, Object> paramsBeforePreflight = new LinkedHashMap<>(currentParams);
            DbSqlPreflightService.RepairContext repairContext = null;
            Map<String, Object> preflightDiagnostics = Map.of();
            if (preflightService != null) {
                try {
                    DbSqlPreflightService.PreflightResult preflight = preflightService.prepare(currentSql, currentParams);
                    currentSql = preflight.sql();
                    currentParams = new LinkedHashMap<>(preflight.params());
                    preflightDiagnostics = preflight.diagnostics() == null ? Map.of() : preflight.diagnostics();
                    repairContext = preflightService.buildRepairContext(currentSql);
                } catch (Exception preflightError) {
                    args.put("preflight_diagnostics", preflightService == null ? Map.of() : preflightService.previewDiagnostics(sqlBeforePreflight));
                    args.put("root_cause_message", preflightError.getMessage());
                    auditPreflightFailure(session, attempt, maxRetries, autoRepairEnabled,
                            sqlBeforePreflight, paramsBeforePreflight, preflightError);
                    lastError = preflightError;
                    log.error("SQL preflight failed (attempt {}/{}): {}", attempt + 1, maxRetries + 1,
                            preflightError.getMessage(), preflightError);
                    failureFeedbackService.recordFailure(new SemanticFailureRecord(
                            session == null ? null : session.getConversationId(),
                            semanticQuestion,
                            sqlBeforePreflight,
                            null,
                            preflightError.getClass().getSimpleName(),
                            preflightError.getMessage(),
                            "POSTGRES_QUERY_PREFLIGHT",
                            Map.of(
                                    "toolCode", toolCode(),
                                    "params", paramsBeforePreflight,
                                    "attempt", attempt + 1,
                                    "maxRetries", maxRetries
                            )
                    ));
                    auditExecution(
                            session,
                            args,
                            originalSql,
                            sqlBeforePreflight,
                            paramsBeforePreflight,
                            List.of(),
                            preflightError,
                            Map.of("status", "ERROR", "phase", "PREFLIGHT"));
                    if (!autoRepairEnabled || attempt >= maxRetries) {
                        break;
                    }
                    DbSqlPreflightService.RepairContext fallbackContext = null;
                    if (preflightService != null) {
                        fallbackContext = preflightService.buildRepairContext(sqlBeforePreflight);
                    }
                    String repairedSql = repairSql(sqlBeforePreflight, paramsBeforePreflight, preflightError,
                            fallbackContext, session, attempt, maxRetries);
                    auditPreflightRepair(session, attempt, maxRetries, sqlBeforePreflight, repairedSql, preflightError);
                    if (repairedSql == null || repairedSql.isBlank() || repairedSql.equalsIgnoreCase(sqlBeforePreflight)) {
                        break;
                    }
                    currentSql = repairedSql;
                    currentParams = new LinkedHashMap<>(paramsBeforePreflight);
                    continue;
                }
            }
            auditPreflight(
                    session,
                    attempt,
                    maxRetries,
                    autoRepairEnabled,
                    sqlBeforePreflight,
                    currentSql,
                    paramsBeforePreflight,
                    currentParams,
                    preflightDiagnostics,
                    repairContext);

            ReconcileResult reconcileResult = reconcileSqlBeforeExecution(
                    currentSql,
                    currentParams,
                    preflightDiagnostics,
                    repairContext,
                    session,
                    attempt,
                    maxRetries,
                    autoRepairEnabled);
            currentSql = reconcileResult.sql();
            currentParams = new LinkedHashMap<>(reconcileResult.params());
            args.put("sql_reconcile_status", reconcileResult.status());
            args.put("sql_reconcile_attempt_count", reconcileResult.attemptCount());
            args.put("semantic_type_mismatches", reconcileResult.semanticTypeMismatches());
            args.put("last_repaired_sql", reconcileResult.sql());
            if (!reconcileResult.diagnostics().isEmpty()) {
                args.put("sql_reconcile_diagnostics", reconcileResult.diagnostics());
            }
            sqlGuardrail.assertReadOnly(currentSql, "postgres.query tool (post-preflight)");
            log.debug("Executing dynamic SQL from MCP (attempt {}/{}): {}", attempt + 1, maxRetries + 1, currentSql);

            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(currentSql, currentParams);
                args.put("query", currentSql);
                Map<String, Object> resultPayload = buildSuccessPayload(
                        rows,
                        session,
                        semanticQuestion,
                        currentSql,
                        currentParams);
                resultPayload.put("sql_reconcile_status", reconcileResult.status());
                if (reconcileResult.reconcileErrorMessage() != null && !reconcileResult.reconcileErrorMessage().isBlank()) {
                    resultPayload.put("sql_reconcile_error", reconcileResult.reconcileErrorMessage());
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> auditedRows = (List<Map<String, Object>>) resultPayload.getOrDefault("rows", rows);
                auditExecution(
                        session,
                        args,
                        originalSql,
                        currentSql,
                        currentParams,
                        auditedRows,
                        null,
                        resultPayload);
                return resultPayload;
            } catch (Exception e) {
                lastError = e;
                args.put("preflight_diagnostics", preflightDiagnostics == null ? Map.of() : preflightDiagnostics);
                args.put("root_cause_message", e.getMessage());
                args.put("original_sql", originalSql);
                log.error("Failed to execute dynamic LLM SQL query (attempt {}/{}): {}", attempt + 1, maxRetries + 1,
                        e.getMessage(), e);
                failureFeedbackService.recordFailure(new SemanticFailureRecord(
                        session == null ? null : session.getConversationId(),
                        semanticQuestion,
                        currentSql,
                        null,
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        "POSTGRES_QUERY_EXECUTION",
                        Map.of(
                                "toolCode", toolCode(),
                                "params", currentParams,
                                "attempt", attempt + 1,
                                "maxRetries", maxRetries
                        )
                ));

                auditExecution(
                        session,
                        args,
                        originalSql,
                        currentSql,
                        currentParams,
                        List.of(),
                        e,
                        Map.of("status", "ERROR"));

                if (!autoRepairEnabled || attempt >= maxRetries) {
                    break;
                }

                String repairedSql = repairSql(currentSql, currentParams, e, repairContext, session, attempt, maxRetries);
                auditPreflightRepair(session, attempt, maxRetries, currentSql, repairedSql, e);
                if (repairedSql == null || repairedSql.isBlank() || repairedSql.equalsIgnoreCase(currentSql)) {
                    break;
                }
                currentSql = repairedSql;
            }
        }

        throw new IllegalArgumentException(TOOL_EXECUTION_FAILED, lastError);
    }

    private ReconcileResult reconcileSqlBeforeExecution(
            String sql,
            Map<String, Object> params,
            Map<String, Object> preflightDiagnostics,
            DbSqlPreflightService.RepairContext repairContext,
            EngineSession session,
            int attempt,
            int maxRetries,
            boolean llmEnabled) {
        String currentSql = sql == null ? "" : sql;
        Map<String, Object> currentParams = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        int reconcileAttempts = 0;
        Set<String> mismatches = new HashSet<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        String status = "OK";
        String reconcileErrorMessage = null;

        DeterministicTypeFix deterministic = applyDeterministicTypeFix(currentSql, currentParams, preflightDiagnostics, repairContext);
        currentParams = deterministic.params();
        mismatches.addAll(deterministic.mismatches());
        diagnostics.put("deterministic_coercions", deterministic.coercions());
        diagnostics.put("deterministic_mismatches", deterministic.mismatches());
        reconcileAttempts++;

        if (llmEnabled) {
            LlmClient llmClient = llmClientProvider == null ? null : llmClientProvider.getIfAvailable();
            if (llmClient != null) {
                String reconciledSql = reconcileSqlWithLlm(
                        llmClient,
                        currentSql,
                        currentParams,
                        preflightDiagnostics,
                        repairContext,
                        session,
                        attempt,
                        maxRetries,
                        diagnostics);
                if (reconciledSql != null && !reconciledSql.isBlank()) {
                    String normalized = normalizeSql(reconciledSql);
                    if (!normalized.isBlank()) {
                        currentSql = normalized;
                    }
                } else if ("ERROR".equals(String.valueOf(diagnostics.getOrDefault("llm_mode", "")))) {
                    status = "DEGRADED";
                    reconcileErrorMessage = String.valueOf(diagnostics.getOrDefault("llm_error", ""));
                }
                reconcileAttempts++;
            }
        }
        auditReconcileFinal(session, attempt, maxRetries, sql, currentSql, currentParams, mismatches, diagnostics, status, reconcileErrorMessage);
        return new ReconcileResult(currentSql, currentParams, reconcileAttempts, new ArrayList<>(mismatches), diagnostics, status, reconcileErrorMessage);
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

    private Map<String, Object> buildSuccessPayload(
            List<Map<String, Object>> rows,
            EngineSession session,
            String semanticQuestion,
            String sql,
            Map<String, Object> queryParams) {
        Map<String, Object> resultPayload = new LinkedHashMap<>();
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

        if (rows.size() > 100) {
            log.warn("Query returned {} rows, truncating to 100 for LLM context safety", rows.size());
            resultPayload.put("warning", "Result set truncated from " + rows.size() + " to 100 rows.");
            rows = rows.subList(0, 100);
        }
        resultPayload.put("rows", rows);
        return resultPayload;
    }

    private void auditExecution(
            EngineSession session,
            Map<String, Object> args,
            String originalSql,
            String sql,
            Map<String, Object> queryParams,
            List<Map<String, Object>> rows,
            Exception executionError,
            Map<String, Object> resultPayload) {
        Map<String, Object> basePayload = new LinkedHashMap<>();
        basePayload.put("tool_code", toolCode());
        basePayload.put("args", args);
        if (!originalSql.equals(sql)) {
            basePayload.put("normalized_sql", sql);
        }
        if (resultPayload != null && resultPayload.containsKey("warning")) {
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
    }

    private void auditPreflight(
            EngineSession session,
            int attempt,
            int maxRetries,
            boolean autoRepairEnabled,
            String sqlBeforePreflight,
            String sqlAfterPreflight,
            Map<String, Object> paramsBefore,
            Map<String, Object> paramsAfter,
            Map<String, Object> preflightDiagnostics,
            DbSqlPreflightService.RepairContext context) {
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("attempt", attempt + 1);
        meta.put("max_retries", maxRetries);
        meta.put("sql_auto_repair_enabled", autoRepairEnabled);
        meta.put("tool_code", toolCode());
        payload.put("_meta", meta);
        payload.put("sql_before", sqlBeforePreflight);
        payload.put("sql_after", sqlAfterPreflight);
        payload.put("params_before", paramsBefore == null ? Map.of() : paramsBefore);
        payload.put("params_after", paramsAfter == null ? Map.of() : paramsAfter);
        payload.put("preflight_diagnostics", preflightDiagnostics == null ? Map.of() : preflightDiagnostics);
        payload.put("schema_knowledge_used", context == null ? Map.of() : context.schemaDetails());
        payload.put("semantic_knowledge_used", context == null ? Map.of() : context.semanticHints());
        auditService.audit(ConvEngineAuditStage.MCP_DB_PREFLIGHT, session.getConversationId(), payload);
    }

    private void auditPreflightRepair(
            EngineSession session,
            int attempt,
            int maxRetries,
            String failedSql,
            String repairedSql,
            Exception error) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("attempt", attempt + 1);
        meta.put("max_retries", maxRetries);
        meta.put("tool_code", toolCode());
        payload.put("_meta", meta);
        payload.put("failed_sql", failedSql);
        payload.put("repaired_sql", repairedSql);
        payload.put("error", String.valueOf(error == null ? "" : error.getMessage()));
        auditService.audit(ConvEngineAuditStage.MCP_DB_PREFLIGHT_REPAIR, session.getConversationId(), payload);
    }

    private void auditPreflightFailure(
            EngineSession session,
            int attempt,
            int maxRetries,
            boolean autoRepairEnabled,
            String sqlBeforePreflight,
            Map<String, Object> paramsBefore,
            Exception error) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("attempt", attempt + 1);
        meta.put("max_retries", maxRetries);
        meta.put("sql_auto_repair_enabled", autoRepairEnabled);
        meta.put("tool_code", toolCode());
        payload.put("_meta", meta);
        payload.put("status", "ERROR");
        payload.put("sql_before", sqlBeforePreflight);
        payload.put("params_before", paramsBefore == null ? Map.of() : paramsBefore);
        if (preflightService != null) {
            try {
                payload.put("preflight_diagnostics", preflightService.previewDiagnostics(sqlBeforePreflight));
            } catch (Exception ignored) {
                payload.put("preflight_diagnostics", Map.of());
            }
        }
        if (preflightService != null) {
            try {
                DbSqlPreflightService.RepairContext context = preflightService.buildRepairContext(sqlBeforePreflight);
                payload.put("schema_knowledge_used", context == null ? Map.of() : context.schemaDetails());
                payload.put("semantic_knowledge_used", context == null ? Map.of() : context.semanticHints());
            } catch (Exception ignored) {
                payload.put("schema_knowledge_used", Map.of());
                payload.put("semantic_knowledge_used", Map.of());
            }
        }
        payload.put("error_class", error == null ? null : error.getClass().getName());
        payload.put("error_message", error == null ? null : error.getMessage());
        auditService.audit(ConvEngineAuditStage.MCP_DB_PREFLIGHT, session.getConversationId(), payload);
    }

    private boolean isSqlAutoRepairEnabled() {
        return mcpConfig != null
                && mcpConfig.getDb() != null
                && mcpConfig.getDb().getPreflight() != null
                && mcpConfig.getDb().getPreflight().isSqlAutoRepairEnabled();
    }

    private int maxSqlAutoRepairRetries() {
        if (mcpConfig == null || mcpConfig.getDb() == null || mcpConfig.getDb().getPreflight() == null) {
            return 0;
        }
        return Math.max(0, mcpConfig.getDb().getPreflight().getSqlAutoRepairMaxRetries());
    }

    private String repairSql(
            String sql,
            Map<String, Object> params,
            Exception error,
            DbSqlPreflightService.RepairContext repairContext,
            EngineSession session,
            int attempt,
            int maxRetries) {
        LlmClient llmClient = llmClientProvider == null ? null : llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            return null;
        }

        String schemaJsonForPrompt = toJsonSafe(repairContext == null ? Map.of() : repairContext.schemaDetails());
        String semanticJson = toJsonSafe(repairContext == null ? Map.of() : repairContext.semanticHints());
        String paramsJson = toJsonSafe(params == null ? Map.of() : params);

        String systemPrompt = resolveConfig(CFG_DB_SQL_PREFLIGHT_SYSTEM_PROMPT, DEFAULT_DB_SQL_PREFLIGHT_SYSTEM_PROMPT);
        String userPromptTemplate = resolveConfig(CFG_DB_SQL_PREFLIGHT_USER_PROMPT, DEFAULT_DB_SQL_PREFLIGHT_USER_PROMPT);
        String schemaResponseJson = resolveConfig(CFG_DB_SQL_PREFLIGHT_SCHEMA_JSON, DEFAULT_DB_SQL_PREFLIGHT_SCHEMA_JSON);

        String llmContextJson = buildRepairContextJson(
                sql,
                paramsJson,
                schemaJsonForPrompt,
                semanticJson,
                String.valueOf(error == null ? "" : error.getMessage()));

        String prompt = systemPrompt + "\n\n" + applyPromptVars(userPromptTemplate, Map.of(
                "sql_before", sql == null ? "" : sql,
                "params_json", paramsJson,
                "schema_json", schemaJsonForPrompt,
                "semantic_json", semanticJson,
                "context_json", llmContextJson,
                "error_message", String.valueOf(error == null ? "" : error.getMessage())
        ));
        auditRepairLlmInput(session, attempt, maxRetries, sql, params, error, llmContextJson);
        String repaired = extractSqlFromJsonOrFallback(
                llmClient,
                session,
                prompt,
                schemaResponseJson,
                llmContextJson,
                attempt,
                maxRetries);
        return normalizeSql(repaired);
    }

    private String reconcileSqlWithLlm(
            LlmClient llmClient,
            String sql,
            Map<String, Object> params,
            Map<String, Object> preflightDiagnostics,
            DbSqlPreflightService.RepairContext repairContext,
            EngineSession session,
            int attempt,
            int maxRetries,
            Map<String, Object> diagnosticsOut) {
        String schemaJson = toJsonSafe(repairContext == null ? Map.of() : repairContext.schemaDetails());
        String semanticJson = toJsonSafe(repairContext == null ? Map.of() : repairContext.semanticHints());
        String preflightJson = toJsonSafe(preflightDiagnostics == null ? Map.of() : preflightDiagnostics);
        String paramsJson = toJsonSafe(params == null ? Map.of() : params);
        String systemPrompt = resolveConfig(CFG_DB_SQL_RECONCILE_SYSTEM_PROMPT, DEFAULT_DB_SQL_RECONCILE_SYSTEM_PROMPT);
        String userPrompt = resolveConfig(CFG_DB_SQL_RECONCILE_USER_PROMPT, DEFAULT_DB_SQL_RECONCILE_USER_PROMPT);
        String schema = resolveConfig(CFG_DB_SQL_RECONCILE_SCHEMA_JSON, DEFAULT_DB_SQL_RECONCILE_SCHEMA_JSON);
        String contextJson = toJsonSafe(Map.of(
                "sql_before", sql == null ? "" : sql,
                "params", params == null ? Map.of() : params,
                "preflight_diagnostics", preflightDiagnostics == null ? Map.of() : preflightDiagnostics,
                "runtime_schema_details", repairContext == null ? Map.of() : repairContext.schemaDetails(),
                "semantic_hints", repairContext == null ? Map.of() : repairContext.semanticHints()
        ));

        String prompt = systemPrompt + "\n\n" + applyPromptVars(userPrompt, Map.of(
                "sql_before", sql == null ? "" : sql,
                "params_json", paramsJson,
                "preflight_json", preflightJson,
                "schema_json", schemaJson,
                "semantic_json", semanticJson
        ));
        auditReconcileInput(session, attempt, maxRetries, sql, params, preflightDiagnostics, repairContext, contextJson);
        try {
            String out = llmClient.generateJsonStrict(session, prompt, schema, contextJson);
            Map<?, ?> parsed = mapper.readValue(out, Map.class);
            Object sqlObj = parsed.get("sql");
            Object paramsObj = parsed.get("params");
            if (paramsObj instanceof Map<?, ?> map) {
                map.forEach((k, v) -> params.put(String.valueOf(k), coerceQueryParam(v)));
            }
            String reconciledSql = sqlObj == null ? null : String.valueOf(sqlObj);
            auditReconcileOutput(session, attempt, maxRetries, sql, reconciledSql, params, "STRICT_JSON");
            diagnosticsOut.put("llm_mode", "STRICT_JSON");
            diagnosticsOut.put("llm_reconciled", reconciledSql != null && !reconciledSql.isBlank());
            return reconciledSql;
        } catch (Exception ex) {
            auditReconcileError(session, attempt, maxRetries, ex, sql, params);
            diagnosticsOut.put("llm_mode", "ERROR");
            diagnosticsOut.put("llm_error", ex.getMessage());
            return null;
        }
    }

    private String resolveConfig(String key, String defaultValue) {
        if (configResolver == null) {
            return defaultValue;
        }
        return configResolver.resolveString(this, key, defaultValue);
    }

    private String buildRepairContextJson(
            String sql,
            String paramsJson,
            String schemaJsonForPrompt,
            String semanticJson,
            String errorMessage) {
        Map<String, Object> context = new HashMap<>();
        context.put("original_sql", sql == null ? "" : sql);
        context.put("params_json", paramsJson);
        context.put("runtime_schema_details", schemaJsonForPrompt);
        context.put("semantic_hints", semanticJson);
        context.put("error_message", errorMessage == null ? "" : errorMessage);
        return toJsonSafe(context);
    }

    private String extractSqlFromJsonOrFallback(
            LlmClient llmClient,
            EngineSession session,
            String prompt,
            String schemaJson,
            String contextJson,
            int attempt,
            int maxRetries) {
        try {
            String out = llmClient.generateJsonStrict(session, prompt, schemaJson, contextJson == null ? "{}" : contextJson);
            Map<?, ?> parsed = mapper.readValue(out, Map.class);
            Object sql = parsed.get("sql");
            if (sql != null && !String.valueOf(sql).isBlank()) {
                auditRepairLlmOutput(session, attempt, maxRetries, String.valueOf(sql), "STRICT_JSON");
                return String.valueOf(sql);
            }
        } catch (Exception ignored) {
            auditRepairLlmError(session, attempt, maxRetries, ignored);
        }
        String fallback = llmClient.generateText(session, prompt, contextJson == null ? "{}" : contextJson);
        auditRepairLlmOutput(session, attempt, maxRetries, fallback, "TEXT_FALLBACK");
        return fallback;
    }

    private void auditRepairLlmInput(
            EngineSession session,
            int attempt,
            int maxRetries,
            String sql,
            Map<String, Object> params,
            Exception error,
            String contextJson) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("sql_before_repair", sql);
        payload.put("params", params == null ? Map.of() : params);
        payload.put("repair_reason", error == null ? null : error.getMessage());
        payload.put("repair_context_json", contextJson);
        auditService.audit(AUDIT_SQL_REPAIR_LLM_INPUT, session.getConversationId(), payload);
    }

    private void auditReconcileInput(
            EngineSession session,
            int attempt,
            int maxRetries,
            String sql,
            Map<String, Object> params,
            Map<String, Object> preflightDiagnostics,
            DbSqlPreflightService.RepairContext context,
            String contextJson) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("sql_before", sql);
        payload.put("params_before", params == null ? Map.of() : params);
        payload.put("preflight_diagnostics", preflightDiagnostics == null ? Map.of() : preflightDiagnostics);
        payload.put("schema_knowledge_used", context == null ? Map.of() : context.schemaDetails());
        payload.put("semantic_knowledge_used", context == null ? Map.of() : context.semanticHints());
        payload.put("reconcile_context_json", contextJson);
        auditService.audit(AUDIT_SQL_RECONCILE_INPUT, session.getConversationId(), payload);
    }

    private void auditReconcileOutput(
            EngineSession session,
            int attempt,
            int maxRetries,
            String beforeSql,
            String afterSql,
            Map<String, Object> params,
            String mode) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("mode", mode);
        payload.put("sql_after", afterSql);
        payload.put("params_after", params == null ? Map.of() : params);
        payload.put("changed", afterSql != null && !afterSql.isBlank() && !afterSql.equals(beforeSql));
        auditService.audit(AUDIT_SQL_RECONCILE_OUTPUT, session.getConversationId(), payload);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("tool_code", toolCode());
        diff.put("attempt", attempt + 1);
        diff.put("before_sql", beforeSql);
        diff.put("after_sql", afterSql);
        diff.put("changed", afterSql != null && !afterSql.isBlank() && !afterSql.equals(beforeSql));
        auditService.audit(AUDIT_SQL_RECONCILE_DIFF, session.getConversationId(), diff);
    }

    private void auditReconcileError(
            EngineSession session,
            int attempt,
            int maxRetries,
            Exception ex,
            String sql,
            Map<String, Object> params) {
        if (session == null || session.getConversationId() == null || auditService == null || ex == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("sql_before", sql);
        payload.put("params_before", params == null ? Map.of() : params);
        payload.put("recoverable", true);
        payload.put("error_class", ex.getClass().getName());
        payload.put("error_message", ex.getMessage());
        payload.put("error_stack_trace", stackTraceOf(ex));
        auditService.audit(AUDIT_SQL_RECONCILE_ERROR, session.getConversationId(), payload);
    }

    private void auditReconcileFinal(
            EngineSession session,
            int attempt,
            int maxRetries,
            String originalSql,
            String finalSql,
            Map<String, Object> finalParams,
            Set<String> mismatches,
            Map<String, Object> diagnostics,
            String status,
            String errorMessage) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("original_sql", originalSql);
        payload.put("final_sql", finalSql);
        payload.put("final_params", finalParams == null ? Map.of() : finalParams);
        payload.put("status", status == null ? "OK" : status);
        payload.put("recoverable", !"ERROR".equalsIgnoreCase(status));
        payload.put("error_message", errorMessage);
        payload.put("semantic_type_mismatches", mismatches == null ? List.of() : new ArrayList<>(mismatches));
        payload.put("diagnostics", diagnostics == null ? Map.of() : diagnostics);
        auditService.audit(AUDIT_SQL_RECONCILE_FINAL, session.getConversationId(), payload);
    }

    private void auditRepairLlmOutput(
            EngineSession session,
            int attempt,
            int maxRetries,
            String repairedSql,
            String mode) {
        if (session == null || session.getConversationId() == null || auditService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("mode", mode);
        payload.put("repaired_sql", repairedSql);
        auditService.audit(AUDIT_SQL_REPAIR_LLM_OUTPUT, session.getConversationId(), payload);
    }

    private void auditRepairLlmError(
            EngineSession session,
            int attempt,
            int maxRetries,
            Exception error) {
        if (session == null || session.getConversationId() == null || auditService == null || error == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_code", toolCode());
        payload.put("attempt", attempt + 1);
        payload.put("max_retries", maxRetries);
        payload.put("error_class", error.getClass().getName());
        payload.put("error_message", error.getMessage());
        auditService.audit(AUDIT_SQL_REPAIR_LLM_ERROR, session.getConversationId(), payload);
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

    private DeterministicTypeFix applyDeterministicTypeFix(
            String sql,
            Map<String, Object> params,
            Map<String, Object> preflightDiagnostics,
            DbSqlPreflightService.RepairContext context) {
        if (sql == null || sql.isBlank() || params == null || params.isEmpty()) {
            return new DeterministicTypeFix(params == null ? Map.of() : params, List.of(), 0);
        }
        Map<String, String> aliasToTable = extractAliasToTable(sql, preflightDiagnostics);
        Map<String, String> columnTypes = extractColumnTypeMap(context);
        Map<String, Object> coerced = new LinkedHashMap<>(params);
        List<String> mismatches = new ArrayList<>();
        int coercions = 0;
        Matcher matcher = PARAM_EQUALITY_PATTERN.matcher(sql);
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            String column = matcher.group(2).toLowerCase(Locale.ROOT);
            String param = matcher.group(3);
            String table = aliasToTable.get(alias);
            if (table == null) {
                continue;
            }
            String type = columnTypes.get((table + "." + column).toLowerCase(Locale.ROOT));
            if (type == null || !isNumericType(type) || !coerced.containsKey(param)) {
                continue;
            }
            Object value = coerced.get(param);
            if (!(value instanceof String text)) {
                continue;
            }
            String trimmed = text.trim();
            if (trimmed.matches("[-+]?[0-9]+")) {
                coerced.put(param, Long.parseLong(trimmed));
                coercions++;
            } else if (trimmed.matches("[-+]?[0-9]+\\.[0-9]+")) {
                coerced.put(param, Double.parseDouble(trimmed));
                coercions++;
            } else {
                mismatches.add(table + "." + column + " <= :" + param + " value=" + trimmed);
            }
        }
        return new DeterministicTypeFix(coerced, mismatches, coercions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractAliasToTable(String sql, Map<String, Object> preflightDiagnostics) {
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        if (preflightDiagnostics != null) {
            Object aliasMap = preflightDiagnostics.get("aliasToTable");
            if (aliasMap instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    if (k != null && v != null) {
                        aliasToTable.put(String.valueOf(k).toLowerCase(Locale.ROOT), String.valueOf(v).toLowerCase(Locale.ROOT));
                    }
                });
            }
        }
        if (!aliasToTable.isEmpty()) {
            return aliasToTable;
        }
        collectAliases(FROM_PATTERN.matcher(sql == null ? "" : sql), aliasToTable);
        collectAliases(JOIN_PATTERN.matcher(sql == null ? "" : sql), aliasToTable);
        return aliasToTable;
    }

    private void collectAliases(Matcher matcher, Map<String, String> aliasToTable) {
        while (matcher.find()) {
            String table = normalizeTableName(matcher.group(1));
            String alias = matcher.group(2);
            if (table == null || table.isBlank()) {
                continue;
            }
            aliasToTable.put(table, table);
            if (alias != null && !alias.isBlank()) {
                aliasToTable.put(alias.toLowerCase(Locale.ROOT), table);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractColumnTypeMap(DbSqlPreflightService.RepairContext context) {
        Map<String, String> out = new LinkedHashMap<>();
        if (context == null || context.schemaDetails() == null) {
            return out;
        }
        Object schemaObj = context.schemaDetails();
        if (!(schemaObj instanceof Map<?, ?> schemaMap)) {
            return out;
        }
        for (Map.Entry<?, ?> tableEntry : schemaMap.entrySet()) {
            if (tableEntry.getKey() == null || !(tableEntry.getValue() instanceof Map<?, ?> tableData)) {
                continue;
            }
            String table = normalizeTableName(String.valueOf(tableEntry.getKey()));
            Object colsObj = tableData.get("columns");
            if (!(colsObj instanceof List<?> cols)) {
                continue;
            }
            for (Object colObj : cols) {
                if (!(colObj instanceof Map<?, ?> colMap)) {
                    continue;
                }
                Object colName = colMap.get("name");
                Object typeName = colMap.get("typeName");
                if (colName == null || typeName == null) {
                    continue;
                }
                out.put((table + "." + String.valueOf(colName).toLowerCase(Locale.ROOT)),
                        String.valueOf(typeName).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private String normalizeTableName(String tableRef) {
        if (tableRef == null || tableRef.isBlank()) {
            return null;
        }
        return tableRef.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isNumericType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String t = typeName.toLowerCase(Locale.ROOT);
        return t.contains("int")
                || t.contains("numeric")
                || t.contains("decimal")
                || t.contains("float")
                || t.contains("double")
                || t.contains("real");
    }

    private String toJsonSafe(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String stackTraceOf(Exception ex) {
        if (ex == null) {
            return "";
        }
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Exception ignored) {
            return ex.toString();
        }
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

    private record DeterministicTypeFix(Map<String, Object> params, List<String> mismatches, int coercions) {}
    private record ReconcileResult(
            String sql,
            Map<String, Object> params,
            int attemptCount,
            List<String> semanticTypeMismatches,
            Map<String, Object> diagnostics,
            String status,
            String reconcileErrorMessage) {}
}
