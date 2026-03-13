package com.github.salilvnair.convengine.engine.mcp.query.semantic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticAmbiguity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticCompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticGuardrailResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticQueryResponseV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SemanticLlmQueryService {

    private static final String TOOL_CODE = "db.semantic.query";
    private static final String VERSION = "v2-llm";
    private static final int DEFAULT_SQL_RETRY_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_FAILURE_EXAMPLE_TOP_K = 3;
    private static final int DEFAULT_FAILURE_EXAMPLE_CANDIDATE_LIMIT = 120;
    private static final Pattern FROM_ALIAS_PATTERN = Pattern.compile("(?is)\\bfrom\\s+([a-zA-Z_][\\w.]*)\\s+([a-zA-Z_][\\w]*)");
    private static final Pattern QUALIFIED_COLUMN_PATTERN = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\.([a-zA-Z_][\\w]*)\\b");
    private static final Pattern DATE_ONLY_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final LlmClient llmClient;
    private final StaticConfigurationCacheService staticCacheService;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final McpSqlGuardrail sqlGuardrail;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private String querySystemPrompt;
    private String queryUserPrompt;
    private String querySchema;

    @PostConstruct
    public void init() {
        this.querySystemPrompt = configResolver.resolveString(this, "SYSTEM_PROMPT", defaultSystemPrompt());
        this.queryUserPrompt = configResolver.resolveString(this, "USER_PROMPT", defaultUserPrompt());
        this.querySchema = normalizeSchema(
                configResolver.resolveString(this, "SCHEMA_PROMPT", defaultSchemaPrompt())
        );
    }

    public SemanticQueryResponseV2 query(CanonicalIntent canonicalIntent, String question, EngineSession session) {
        UUID conversationId = session == null ? null : session.getConversationId();
        String safeQuestion = question == null ? "" : question.trim();
        String retrievalQuery = resolveStandaloneQuestion(session, safeQuestion);
        CanonicalIntent safeIntent = canonicalIntent == null
                ? new CanonicalIntent("LIST_REQUESTS", "DISCONNECT_REQUEST", "LIST_REQUESTS", List.of(), null, List.of(), 100)
                : canonicalIntent;

        Map<String, Object> metadataScope = buildMetadataScope(safeIntent);
        String timezone = ZoneId.systemDefault().getId();
        String nowDate = LocalDate.now(ZoneId.of(timezone)).toString();
        Map<String, Object> promptVars = new LinkedHashMap<>();
        promptVars.put("question", safeQuestion);
        promptVars.put("canonical_intent_json", safeJson(safeIntent));
        promptVars.put("metadata_scope_json", safeJson(metadataScope));
        List<Map<String, Object>> failureExamples = loadFailureSqlExamples(retrievalQuery, safeIntent);
        promptVars.put("retrieved_failure_examples_json", safeJson(failureExamples));
        List<String> failureHints = loadRecentFailureHints(safeQuestion, safeIntent);
        promptVars.put("recent_failure_hints_json", safeJson(failureHints));

        PromptTemplateContext ctx = PromptTemplateContext.builder()
                .templateName("SemanticLlmQueryService")
                .systemPrompt(querySystemPrompt)
                .userPrompt(queryUserPrompt)
                .context("{}")
                .userInput(safeQuestion)
                .resolvedUserInput(safeQuestion)
                .standaloneQuery(safeQuestion)
                .question(safeQuestion)
                .currentDate(nowDate)
                .currentTimezone(timezone)
                .extra(promptVars)
                .build();
        String renderedSystem = promptTemplateRenderer.render(querySystemPrompt, ctx);
        String renderedUser = promptTemplateRenderer.render(queryUserPrompt, ctx);
        String renderedUserWithHints = appendFailureContext(renderedUser, failureHints, failureExamples);

        Map<String, Object> promptVarsAudit = new LinkedHashMap<>();
        promptVarsAudit.put("tool", TOOL_CODE);
        promptVarsAudit.put("version", VERSION);
        promptVarsAudit.put("promptVars", promptVars);
        audit("SEMANTIC_QUERY_LLM_PROMPT_VARS", conversationId, promptVarsAudit, session, false);

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("tool", TOOL_CODE);
        inputPayload.put("version", VERSION);
        inputPayload.put("semantic_v2_stage", "query");
        inputPayload.put("semantic_v2_event", "input");
        inputPayload.put("question", safeQuestion);
        inputPayload.put("canonicalIntent", safeIntent);
        inputPayload.put("metadataScope", metadataScope);
        inputPayload.put("resolvedVars", promptVars);
        inputPayload.put("system_prompt", querySystemPrompt);
        inputPayload.put("user_prompt", queryUserPrompt);
        inputPayload.put("resolved_prompt_system", renderedSystem);
        inputPayload.put("resolved_prompt_user", renderedUserWithHints);
        inputPayload.put("schema", querySchema);
        audit("SEMANTIC_QUERY_LLM_INPUT", conversationId, inputPayload, session, false);

        try {
            QueryAttempt attempt = generateAttempt(session, renderedSystem, renderedUserWithHints, "{}");
            ValidationResult validation = ValidationResult.ok();
            SemanticGuardrailResult guardrail = new SemanticGuardrailResult(true, null);
            int maxRetries = Math.max(0, configResolver.resolveInt(this, "SQL_RETRY_MAX_ATTEMPTS", DEFAULT_SQL_RETRY_MAX_ATTEMPTS));
            for (int retry = 0; ; retry++) {
                validation = validateSqlCandidate(attempt.sql(), metadataScope, safeIntent);
                guardrail = applyGuardrails(attempt.sql(), attempt.params(), session);
                if (validation.valid() && Boolean.TRUE.equals(guardrail.allowed())) {
                    break;
                }
                if (retry >= maxRetries) {
                    String finalReason = validation.valid() ? guardrail.reason() : validation.reason();
                    attempt = attempt.withUnsupported(finalReason);
                    break;
                }
                String failureReason = validation.valid() ? guardrail.reason() : validation.reason();
                attempt = regenerateWithFeedback(
                        session,
                        renderedSystem,
                        renderedUserWithHints,
                        attempt,
                        failureReason,
                        failureHints,
                        retry + 1,
                        maxRetries,
                        conversationId
                );
            }

            boolean needsClarification = attempt.needsClarification();
            String clarificationQuestion = attempt.clarificationQuestion();
            boolean unsupported = attempt.unsupported();
            String unsupportedMessage = attempt.unsupportedMessage();
            double confidence = attempt.confidence();
            String sql = normalizeTimestampParamsInSql(attempt.sql(), attempt.params());
            Map<String, Object> params = attempt.params();

            if (needsClarification && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
                clarificationQuestion = "I need one clarification before generating SQL for this request.";
            }
            if (!needsClarification && (sql == null || sql.isBlank()) && !unsupported) {
                unsupported = true;
                unsupportedMessage = "I could not generate a safe read-only SQL query for this request.";
            }
            if (!needsClarification && !unsupported && !validation.valid()) {
                unsupported = true;
                unsupportedMessage = validation.reason();
            }
            if (!needsClarification && !unsupported && !Boolean.TRUE.equals(guardrail.allowed())) {
                unsupported = true;
                unsupportedMessage = guardrail.reason();
            }

            List<SemanticAmbiguity> ambiguities = needsClarification
                    ? List.of(new SemanticAmbiguity("FIELD", "LLM_QUERY_CLARIFICATION", clarificationQuestion, true, List.of()))
                    : List.of();

            SemanticToolMeta meta = new SemanticToolMeta(
                    TOOL_CODE, VERSION, confidence, needsClarification,
                    needsClarification ? clarificationQuestion : null, ambiguities,
                    !unsupported, unsupported, unsupportedMessage
            );

            SemanticCompiledSql compiledSql = new SemanticCompiledSql(
                    needsClarification || unsupported ? null : sql,
                    needsClarification || unsupported ? Map.of() : params
            );

            SemanticQueryResponseV2 response = new SemanticQueryResponseV2(meta, null, compiledSql, guardrail);

            Map<String, Object> outputPayload = new LinkedHashMap<>();
            outputPayload.put("tool", TOOL_CODE);
            outputPayload.put("version", VERSION);
            outputPayload.put("semantic_v2_stage", "query");
            outputPayload.put("semantic_v2_event", "output");
            outputPayload.put("question", safeQuestion);
            outputPayload.put("confidence", confidence);
            outputPayload.put("needsClarification", needsClarification);
            outputPayload.put("operationSupported", !unsupported);
            outputPayload.put("unsupported", unsupported);
            outputPayload.put("compiledSql", response.compiledSql());
            outputPayload.put("guardrail", response.guardrail());
            audit("SEMANTIC_QUERY_LLM_OUTPUT", conversationId, outputPayload, session, false);
            return response;
        } catch (Exception ex) {
            Map<String, Object> errorPayload = new LinkedHashMap<>();
            errorPayload.put("tool", TOOL_CODE);
            errorPayload.put("version", VERSION);
            errorPayload.put("semantic_v2_stage", "query");
            errorPayload.put("semantic_v2_event", "error");
            errorPayload.put("question", safeQuestion);
            errorPayload.put("errorClass", ex.getClass().getName());
            errorPayload.put("errorMessage", ex.getMessage());
            audit("SEMANTIC_QUERY_LLM_ERROR", conversationId, errorPayload, session, true);
            throw new IllegalStateException("LLM query agent failed: " + ex.getMessage(), ex);
        }
    }

    private QueryAttempt generateAttempt(EngineSession session, String renderedSystem, String renderedUser, String contextJson) throws Exception {
        String raw = llmClient.generateJsonStrict(session, renderedSystem + "\n\n" + renderedUser, querySchema, contextJson);
        JsonNode node = mapper.readTree(raw == null ? "{}" : raw);
        String sql = readText(node, "sql");
        Map<String, Object> params = readParams(node.path("params"));
        boolean needsClarification = node.path("needsClarification").asBoolean(false);
        String clarificationQuestion = readText(node, "clarificationQuestion");
        boolean unsupported = node.path("unsupported").asBoolean(false);
        String unsupportedMessage = readText(node, "unsupportedMessage");
        double confidence = clampConfidence(node.path("confidence").asDouble(0.70d));
        return new QueryAttempt(sql, params, confidence, needsClarification, clarificationQuestion, unsupported, unsupportedMessage, raw);
    }

    private QueryAttempt regenerateWithFeedback(EngineSession session,
                                                String renderedSystem,
                                                String renderedUser,
                                                QueryAttempt prior,
                                                String failureReason,
                                                List<String> failureHints,
                                                int retryNumber,
                                                int maxRetries,
                                                UUID conversationId) throws Exception {
        String feedbackReason = (failureReason == null || failureReason.isBlank())
                ? "Previous SQL failed validation."
                : failureReason;
        String userRetry = renderedUser + "\n\nRetry instructions:\n"
                + "- Previous SQL failed. Generate corrected SQL.\n"
                + "- Previous SQL:\n" + safeSqlBlock(prior.sql()) + "\n"
                + "- Failure reason:\n" + feedbackReason + "\n"
                + "- Avoid repeating recent failures:\n" + safeJson(failureHints) + "\n"
                + "- Ensure base table alias references only columns present on that base table.\n"
                + "- Retry attempt: " + retryNumber + "/" + maxRetries + ".\n";

        Map<String, Object> retryAudit = new LinkedHashMap<>();
        retryAudit.put("tool", TOOL_CODE);
        retryAudit.put("version", VERSION);
        retryAudit.put("semantic_v2_stage", "query");
        retryAudit.put("semantic_v2_event", "retry_input");
        retryAudit.put("retryAttempt", retryNumber);
        retryAudit.put("maxRetries", maxRetries);
        retryAudit.put("failureReason", feedbackReason);
        retryAudit.put("previousSql", prior.sql());
        retryAudit.put("resolved_prompt_user_retry", userRetry);
        audit("SEMANTIC_QUERY_LLM_RETRY_INPUT", conversationId, retryAudit, session, false);

        QueryAttempt retried = generateAttempt(session, renderedSystem, userRetry, "{}");
        Map<String, Object> retryOutputAudit = new LinkedHashMap<>();
        retryOutputAudit.put("tool", TOOL_CODE);
        retryOutputAudit.put("version", VERSION);
        retryOutputAudit.put("semantic_v2_stage", "query");
        retryOutputAudit.put("semantic_v2_event", "retry_output");
        retryOutputAudit.put("retryAttempt", retryNumber);
        retryOutputAudit.put("maxRetries", maxRetries);
        retryOutputAudit.put("json", retried.rawJson());
        audit("SEMANTIC_QUERY_LLM_RETRY_OUTPUT", conversationId, retryOutputAudit, session, false);
        return retried;
    }

    private ValidationResult validateSqlCandidate(String sql, Map<String, Object> metadataScope, CanonicalIntent intent) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.ok();
        }
        String baseAlias = extractBaseAlias(sql);
        String baseTable = extractBaseTable(sql);
        if (baseAlias == null || baseAlias.isBlank() || baseTable == null || baseTable.isBlank()) {
            return ValidationResult.ok();
        }
        Set<String> baseAllowedColumns = baseAllowedColumns(metadataScope, intent, baseTable);
        if (baseAllowedColumns.isEmpty()) {
            return ValidationResult.ok();
        }
        Matcher matcher = QUALIFIED_COLUMN_PATTERN.matcher(sql);
        while (matcher.find()) {
            String alias = matcher.group(1);
            String column = matcher.group(2);
            if (!baseAlias.equalsIgnoreCase(alias)) {
                continue;
            }
            if (!baseAllowedColumns.contains(column.toLowerCase(Locale.ROOT))) {
                return ValidationResult.fail("Invalid base alias column '" + alias + "." + column
                        + "'. Base alias must reference only mapped columns for " + baseTable + ".");
            }
        }
        return ValidationResult.ok();
    }

    private String extractBaseAlias(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = FROM_ALIAS_PATTERN.matcher(sql);
        return matcher.find() ? matcher.group(2) : null;
    }

    private String extractBaseTable(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = FROM_ALIAS_PATTERN.matcher(sql);
        return matcher.find() ? matcher.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    private Set<String> baseAllowedColumns(Map<String, Object> metadataScope, CanonicalIntent intent, String baseTable) {
        if (metadataScope == null || baseTable == null || baseTable.isBlank()) {
            return Set.of();
        }
        String intentEntity = safeUpper(intent == null ? null : intent.entity());
        String queryClass = safeUpper(intent == null ? null : intent.queryClass());
        Object mappingsObj = metadataScope.get("ce_semantic_mapping");
        if (!(mappingsObj instanceof List<?> rows) || rows.isEmpty()) {
            return Set.of();
        }
        Set<String> columns = new LinkedHashSet<>();
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> rowRaw)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) rowRaw;
            String rowEntity = safeUpper(text(row, "entity_key"));
            String rowQueryClass = safeUpper(text(row, "query_class_key"));
            String mappedTable = text(row, "mapped_table");
            String mappedColumn = text(row, "mapped_column");
            if (mappedColumn.isBlank()) {
                continue;
            }
            String normalizedMappedTable = mappedTable.split("\\s+")[0].trim();
            if (!normalizedMappedTable.equalsIgnoreCase(baseTable)) {
                continue;
            }
            if (!intentEntity.isBlank() && !rowEntity.isBlank() && !intentEntity.equals(rowEntity)) {
                continue;
            }
            if (!queryClass.isBlank() && !rowQueryClass.isBlank() && !queryClass.equals(rowQueryClass)) {
                continue;
            }
            columns.add(mappedColumn.toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private String appendFailureContext(String renderedUser, List<String> failureHints, List<Map<String, Object>> failureExamples) {
        String out = renderedUser;
        if (failureExamples != null && !failureExamples.isEmpty()) {
            out = out + "\n\nSimilar past SQL failures and corrections:\n" + safeJson(failureExamples);
        }
        if (failureHints == null || failureHints.isEmpty()) {
            return out;
        }
        return out + "\n\nRecent SQL failure patterns to avoid:\n" + safeJson(failureHints);
    }

    private String safeSqlBlock(String sql) {
        if (sql == null || sql.isBlank()) {
            return "(none)";
        }
        return sql.length() > 4000 ? sql.substring(0, 4000) + "..." : sql;
    }

    private List<String> loadRecentFailureHints(String question, CanonicalIntent intent) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        String queryClass = intent == null ? "" : safeUpper(intent.queryClass());
        List<String> hints = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT root_cause_code, reason
                    FROM ce_semantic_query_failures
                    WHERE stage_code = 'POSTGRES_QUERY_EXECUTION'
                    ORDER BY created_at DESC
                    LIMIT 50
                    """, Map.of());
            for (Map<String, Object> row : rows) {
                String reason = text(row, "reason");
                if (reason.contains("timestamp with time zone >= character varying")
                        || reason.contains("timestamp with time zone <= character varying")) {
                    hints.add("Cast date/time params when comparing to timestamp columns.");
                }
                if (reason.contains("column r.scenario_id does not exist")
                        || reason.contains("column dr.scenario_id does not exist")) {
                    hints.add("Do not reference scenario_id on base request aliases; use it only inside log self-joins.");
                }
                if (reason.contains("bad SQL grammar")) {
                    hints.add("Validate alias.column references against mapped columns before finalizing SQL.");
                }
                if (!queryClass.isBlank() && "TRANSITION_REQUESTS".equals(queryClass)) {
                    hints.add("For transition queries, correlate outer request rows by request_id only.");
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String hint : hints) {
            if (hint == null || hint.isBlank() || out.contains(hint)) {
                continue;
            }
            out.add(hint);
            if (out.size() >= 6) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> loadFailureSqlExamples(String standaloneQuery, CanonicalIntent intent) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || standaloneQuery == null || standaloneQuery.isBlank()) {
            return List.of();
        }
        int topK = Math.max(1, configResolver.resolveInt(this, "FAILURE_EXAMPLE_TOP_K", DEFAULT_FAILURE_EXAMPLE_TOP_K));
        int candidateLimit = Math.max(topK, configResolver.resolveInt(
                this,
                "FAILURE_EXAMPLE_CANDIDATE_LIMIT",
                DEFAULT_FAILURE_EXAMPLE_CANDIDATE_LIMIT
        ));
        List<Float> queryEmbedding = generateEmbedding(standaloneQuery);
        String queryClass = safeUpper(intent == null ? null : intent.queryClass());
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT generated_sql, corrected_sql, reason, root_cause_code, metadata_json, question_embedding
                    FROM ce_semantic_query_failures
                    WHERE stage_code = 'POSTGRES_QUERY_EXECUTION'
                      AND generated_sql IS NOT NULL
                    ORDER BY created_at DESC
                    LIMIT :candidateLimit
                    """, Map.of("candidateLimit", candidateLimit));
            List<FailureExampleCandidate> candidates = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String generatedSql = text(row, "generated_sql");
                if (generatedSql.isBlank()) {
                    continue;
                }
                String correctedSql = text(row, "corrected_sql");
                String reason = text(row, "reason");
                String rootCause = text(row, "root_cause_code");
                Map<String, Object> metadata = parseJsonMap(row.get("metadata_json"));
                String metaQueryClass = safeUpper(text(metadata, "queryClass"));
                if (!queryClass.isBlank() && !metaQueryClass.isBlank() && !queryClass.equals(metaQueryClass)) {
                    continue;
                }
                List<Float> rowEmbedding = extractEmbedding(row.get("question_embedding"));
                if (rowEmbedding.isEmpty()) {
                    rowEmbedding = extractEmbedding(metadata.get("query_embedding"));
                }
                double score = scoreExample(standaloneQuery, queryEmbedding, metadata, rowEmbedding, reason);
                if (score <= 0d) {
                    continue;
                }
                Map<String, Object> example = new LinkedHashMap<>();
                example.put("score", score);
                example.put("reason", reason);
                example.put("rootCause", rootCause);
                example.put("failedSql", generatedSql);
                example.put("correctedSql", correctedSql.isBlank() ? null : correctedSql);
                candidates.add(new FailureExampleCandidate(score, example));
            }
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(FailureExampleCandidate::score).reversed())
                    .limit(topK)
                    .map(FailureExampleCandidate::payload)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double scoreExample(String standaloneQuery,
                                List<Float> queryEmbedding,
                                Map<String, Object> metadata,
                                List<Float> rowEmbedding,
                                String reason) {
        double score = 0d;
        if (queryEmbedding != null && !queryEmbedding.isEmpty() && rowEmbedding != null && !rowEmbedding.isEmpty()) {
            score += Math.max(0d, cosineSimilarity(queryEmbedding, rowEmbedding));
        }
        String rowStandalone = text(metadata, "standalone_query");
        if (rowStandalone.isBlank()) {
            rowStandalone = text(metadata, "standaloneQuery");
        }
        if (!rowStandalone.isBlank()) {
            score += tokenOverlapScore(standaloneQuery, rowStandalone) * 0.35d;
        }
        if (reason != null && reason.contains("timestamp with time zone >= character varying")) {
            score += 0.1d;
        }
        return score;
    }

    private List<Float> generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            float[] vec = llmClient.generateEmbedding(null, text);
            if (vec == null || vec.length == 0) {
                return List.of();
            }
            List<Float> out = new ArrayList<>(vec.length);
            for (float v : vec) {
                out.add(v);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        int dim = Math.min(a.size(), b.size());
        if (dim == 0) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < dim; i++) {
            float av = a.get(i);
            float bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double tokenOverlapScore(String left, String right) {
        Set<String> leftTokens = normalizedTokens(left);
        Set<String> rightTokens = normalizedTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0d;
        }
        long overlap = leftTokens.stream().filter(rightTokens::contains).count();
        return overlap == 0 ? 0d : (double) overlap / (double) Math.max(leftTokens.size(), rightTokens.size());
    }

    private Set<String> normalizedTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            String json = String.valueOf(raw);
            if (json.isBlank()) {
                return Map.of();
            }
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                return Map.of();
            }
            return mapper.convertValue(node, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Float> extractEmbedding(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Float> out = new ArrayList<>();
            for (Object item : list) {
                Float value = toFloat(item);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        if (raw instanceof String text) {
            try {
                JsonNode node = mapper.readTree(text);
                if (!node.isArray()) {
                    return List.of();
                }
                List<Float> out = new ArrayList<>();
                for (JsonNode item : node) {
                    if (item.isNumber()) {
                        out.add(item.floatValue());
                    }
                }
                return out;
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private Float toFloat(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.floatValue();
        }
        try {
            String text = String.valueOf(value).trim();
            if (text.isBlank()) {
                return null;
            }
            return Float.parseFloat(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> buildMetadataScope(CanonicalIntent intent) {
        String queryClass = safeUpper(intent == null ? null : intent.queryClass());
        String entity = safeUpper(intent == null ? null : intent.entity());

        List<Map<String, Object>> mappings = staticCacheService.getAllSemanticMappings();
        List<Map<String, Object>> queryClasses = staticCacheService.getAllSemanticQueryClasses();
        List<Map<String, Object>> synonyms = staticCacheService.getAllSemanticSynonyms();
        List<Map<String, Object>> joinPaths = staticCacheService.getAllSemanticJoinPaths();
        List<Map<String, Object>> concepts = staticCacheService.getAllSemanticConcepts();

        List<Map<String, Object>> scopedMappings = new ArrayList<>();
        for (Map<String, Object> row : mappings) {
            String rowQ = safeUpper(text(row, "query_class_key"));
            String rowE = safeUpper(text(row, "entity_key"));
            boolean queryMatch = queryClass.isBlank() || queryClass.equals(rowQ);
            boolean entityMatch = entity.isBlank() || entity.equals(rowE);
            if (queryMatch && entityMatch) {
                scopedMappings.add(row);
            }
        }
        if (scopedMappings.isEmpty()) {
            scopedMappings = mappings.stream().limit(120).toList();
        }

        Set<String> entityKeys = new LinkedHashSet<>();
        Set<String> conceptKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : scopedMappings) {
            String e = safeUpper(text(row, "entity_key"));
            String c = safeUpper(text(row, "concept_key"));
            if (!e.isBlank()) {
                entityKeys.add(e);
            }
            if (!c.isBlank()) {
                conceptKeys.add(c);
            }
        }
        if (!entity.isBlank()) {
            entityKeys.add(entity);
        }

        Map<String, List<String>> fieldsByEntity = new LinkedHashMap<>();
        for (Map<String, Object> row : scopedMappings) {
            String e = safeUpper(text(row, "entity_key"));
            String field = text(row, "field_key");
            if (e.isBlank() || field.isBlank()) {
                continue;
            }
            fieldsByEntity.computeIfAbsent(e, ignored -> new ArrayList<>());
            List<String> fields = fieldsByEntity.get(e);
            if (!fields.contains(field)) {
                fields.add(field);
            }
        }

        List<Map<String, Object>> scopedQueryClasses = queryClasses.stream()
                .filter(row -> queryClass.isBlank() || queryClass.equals(safeUpper(text(row, "query_class_key"))))
                .toList();

        List<Map<String, Object>> scopedSynonyms = synonyms.stream()
                .filter(row -> conceptKeys.contains(safeUpper(text(row, "concept_key"))))
                .limit(80)
                .toList();

        List<Map<String, Object>> scopedConcepts = concepts.stream()
                .filter(row -> conceptKeys.contains(safeUpper(text(row, "concept_key"))))
                .limit(80)
                .toList();

        List<Map<String, Object>> scopedJoinPaths = joinPaths.stream()
                .filter(row -> {
                    String left = safeUpper(text(row, "left_entity_key"));
                    String right = safeUpper(text(row, "right_entity_key"));
                    return entityKeys.contains(left) || entityKeys.contains(right);
                })
                .limit(80)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed_entity_keys", entityKeys);
        out.put("allowed_fields_by_entity", fieldsByEntity);
        out.put("ce_semantic_query_class", scopedQueryClasses);
        out.put("ce_semantic_mapping", scopedMappings);
        out.put("ce_semantic_synonym", scopedSynonyms);
        out.put("ce_semantic_join_path", scopedJoinPaths);
        out.put("ce_semantic_concept", scopedConcepts);
        return out;
    }

    private String defaultSystemPrompt() {
        return """
                You are SQL Query Agent (Agent-2) for semantic analytics.
                Build a READ-ONLY SQL query and named params from canonical business intent + metadata.

                Rules:
                - Return JSON only.
                - Generate SELECT-only SQL.
                - Never generate INSERT/UPDATE/DELETE/DDL.
                - Use only tables/columns available from ce_semantic_mapping and ce_semantic_query_class.
                - Prefer mapped_table/mapped_column from ce_semantic_mapping.
                - For timestamp/date filters on *_at columns, compare using CAST(:param AS timestamptz) (or typed timestamptz literal).
                - For day filters, prefer half-open ranges: >= start_ts and < next_day_ts.
                - If query cannot be safely generated, set unsupported=true with unsupportedMessage.
                - If clarification is required, set needsClarification=true with clarificationQuestion and do not emit sql.
                """;
    }

    private String defaultUserPrompt() {
        return """
                Current date: {{current_date}}
                Timezone: {{current_timezone}}

                User question:
                {{question}}

                Canonical intent:
                {{canonical_intent_json}}

                Metadata scope:
                {{metadata_scope_json}}
                """;
    }

    private String defaultSchemaPrompt() {
        return """
                {
                  "type":"object",
                  "required":["sql","params","confidence","needsClarification","clarificationQuestion","unsupported","unsupportedMessage"],
                  "properties":{
                    "sql":{"type":["string","null"]},
                    "params":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "required":["key","value"],
                        "properties":{
                          "key":{"type":"string"},
                          "value":{"type":["string","number","boolean","null"]}
                        },
                        "additionalProperties":false
                      }
                    },
                    "confidence":{"type":"number"},
                    "needsClarification":{"type":"boolean"},
                    "clarificationQuestion":{"type":["string","null"]},
                    "unsupported":{"type":"boolean"},
                    "unsupportedMessage":{"type":["string","null"]}
                  },
                  "additionalProperties":false
                }
                """;
    }

    private String normalizeSchema(String rawSchema) {
        String fallback = defaultSchemaPrompt();
        String candidate = rawSchema == null || rawSchema.isBlank() ? fallback : rawSchema;
        try {
            JsonNode rootNode = mapper.readTree(candidate);
            if (!(rootNode instanceof ObjectNode root)) {
                return fallback;
            }
            JsonNode propertiesNode = root.path("properties");
            if (propertiesNode instanceof ObjectNode properties) {
                JsonNode paramsNode = properties.path("params");
                if (paramsNode instanceof ObjectNode params) {
                    params.removeAll();
                    params.put("type", "array");
                    ObjectNode item = mapper.createObjectNode();
                    item.put("type", "object");
                    item.putArray("required").add("key").add("value");
                    ObjectNode itemProps = item.putObject("properties");
                    itemProps.putObject("key").put("type", "string");
                    ObjectNode valueNode = itemProps.putObject("value");
                    valueNode.putArray("type").add("string").add("number").add("boolean").add("null");
                    item.put("additionalProperties", false);
                    params.set("items", item);
                }
            }
            if (!root.has("additionalProperties")) {
                root.put("additionalProperties", false);
            }
            return mapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private SemanticGuardrailResult applyGuardrails(String sql, Map<String, Object> params, EngineSession session) {
        if (sql == null || sql.isBlank()) {
            return new SemanticGuardrailResult(true, null);
        }
        try {
            sqlGuardrail.assertReadOnly(sql, TOOL_CODE + " tool");
            return new SemanticGuardrailResult(true, null);
        } catch (Exception ex) {
            return new SemanticGuardrailResult(false, ex.getMessage());
        }
    }

    private Map<String, Object> readMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<>() {
        });
    }

    private Map<String, Object> readParams(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (node.isObject()) {
            return readMap(node);
        }
        if (node.isArray()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (JsonNode item : node) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String key = item.path("key").asText("");
                if (key == null || key.isBlank()) {
                    continue;
                }
                JsonNode valueNode = item.path("value");
                Object value = valueNode == null || valueNode.isNull() || valueNode.isMissingNode()
                        ? null
                        : mapper.convertValue(valueNode, Object.class);
                out.put(key, value);
            }
            return out;
        }
        return Map.of();
    }

    private String readText(JsonNode node, String key) {
        if (node == null || key == null) {
            return null;
        }
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text == null || text.isBlank() ? null : text;
    }

    private double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, value);
    }

    private String text(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return "";
        }
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeUpper(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed.toUpperCase(Locale.ROOT);
    }

    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String normalizeTimestampParamsInSql(String sql, Map<String, Object> params) {
        if (sql == null || sql.isBlank() || params == null || params.isEmpty()) {
            return sql;
        }
        String normalized = sql;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || !isDateLikeParam(entry.getValue()) || !isTimestampLikeParamName(key)) {
                continue;
            }
            String token = ":" + key;
            String castToken = "CAST(" + token + " AS timestamptz)";
            if (normalized.contains(castToken)) {
                continue;
            }
            normalized = normalized.replace(token, castToken);
        }
        return normalized;
    }

    private boolean isTimestampLikeParamName(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("date") || k.contains("time") || k.contains("from") || k.contains("to") || k.endsWith("_at");
    }

    private boolean isDateLikeParam(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        String text = s.trim();
        if (text.isBlank()) {
            return false;
        }
        if (DATE_ONLY_PATTERN.matcher(text).matches()) {
            return true;
        }
        try {
            java.time.OffsetDateTime.parse(text);
            return true;
        } catch (Exception ignored) {
            // continue
        }
        try {
            java.time.Instant.parse(text);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveStandaloneQuestion(EngineSession session, String fallbackQuestion) {
        if (session != null && session.getStandaloneQuery() != null && !session.getStandaloneQuery().isBlank()) {
            return session.getStandaloneQuery().trim();
        }
        return fallbackQuestion == null ? "" : fallbackQuestion;
    }

    private record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    private record QueryAttempt(String sql,
                                Map<String, Object> params,
                                double confidence,
                                boolean needsClarification,
                                String clarificationQuestion,
                                boolean unsupported,
                                String unsupportedMessage,
                                String rawJson) {
        QueryAttempt withUnsupported(String message) {
            return new QueryAttempt(
                    this.sql,
                    this.params,
                    this.confidence,
                    this.needsClarification,
                    this.clarificationQuestion,
                    true,
                    message,
                    this.rawJson
            );
        }
    }

    private record FailureExampleCandidate(double score, Map<String, Object> payload) {}

    private void audit(String stage, UUID conversationId, Map<String, Object> payload, EngineSession session, boolean error) {
        if (conversationId != null) {
            auditService.audit(stage, conversationId, payload == null ? Map.of() : payload);
        }
        if (session != null && verbosePublisher != null) {
            verbosePublisher.publish(
                    session,
                    "SemanticLlmQueryService",
                    stage,
                    null,
                    TOOL_CODE,
                    error,
                    payload == null ? Map.of() : payload
            );
        }
    }
}
