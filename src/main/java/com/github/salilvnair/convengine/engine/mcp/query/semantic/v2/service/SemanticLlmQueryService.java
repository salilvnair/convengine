package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticAmbiguity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticCompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticGuardrailResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryResponseV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SemanticLlmQueryService {

    private static final String TOOL_CODE = "db.semantic.query";
    private static final String VERSION = "v2-llm";

    private final LlmClient llmClient;
    private final StaticConfigurationCacheService staticCacheService;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final ObjectProvider<List<SemanticSqlPolicyValidator>> validatorsProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
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
        CanonicalIntent safeIntent = canonicalIntent == null
                ? new CanonicalIntent("LIST_REQUESTS", "DISCONNECT_REQUEST", "LIST_REQUESTS", List.of(), null, List.of(), 100)
                : canonicalIntent;

        Map<String, Object> metadataScope = buildMetadataScope(safeIntent);
        String nowDate = LocalDate.now(ZoneOffset.UTC).toString();
        String timezone = ZoneOffset.UTC.getId();
        Map<String, Object> promptVars = new LinkedHashMap<>();
        promptVars.put("question", safeQuestion);
        promptVars.put("canonical_intent_json", safeJson(safeIntent));
        promptVars.put("metadata_scope_json", safeJson(metadataScope));

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
        inputPayload.put("resolved_prompt_user", renderedUser);
        inputPayload.put("schema", querySchema);
        audit("SEMANTIC_QUERY_LLM_INPUT", conversationId, inputPayload, session, false);

        try {
            String raw = llmClient.generateJsonStrict(session, renderedSystem + "\n\n" + renderedUser, querySchema, "{}");
            JsonNode node = mapper.readTree(raw == null ? "{}" : raw);

            String sql = readText(node, "sql");
            Map<String, Object> params = readParams(node.path("params"));
            boolean needsClarification = node.path("needsClarification").asBoolean(false);
            String clarificationQuestion = readText(node, "clarificationQuestion");
            boolean unsupported = node.path("unsupported").asBoolean(false);
            String unsupportedMessage = readText(node, "unsupportedMessage");
            double confidence = clampConfidence(node.path("confidence").asDouble(0.70d));

            if (needsClarification && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
                clarificationQuestion = "I need one clarification before generating SQL for this request.";
            }
            if (!needsClarification && (sql == null || sql.isBlank()) && !unsupported) {
                unsupported = true;
                unsupportedMessage = "I could not generate a safe read-only SQL query for this request.";
            }

            SemanticGuardrailResult guardrail = applyGuardrails(sql, params, session);
            if (!Boolean.TRUE.equals(guardrail.allowed()) && !needsClarification) {
                unsupported = true;
                unsupportedMessage = guardrail.reason();
            }

            List<SemanticAmbiguity> ambiguities = needsClarification
                    ? List.of(new SemanticAmbiguity(
                    "FIELD",
                    "LLM_QUERY_CLARIFICATION",
                    clarificationQuestion,
                    true,
                    List.of()))
                    : List.of();

            SemanticToolMeta meta = new SemanticToolMeta(
                    TOOL_CODE,
                    VERSION,
                    confidence,
                    needsClarification,
                    needsClarification ? clarificationQuestion : null,
                    ambiguities,
                    unsupported,
                    unsupportedMessage
            );

            SemanticCompiledSql compiledSql = new SemanticCompiledSql(
                    needsClarification || unsupported ? null : sql,
                    needsClarification || unsupported ? Map.of() : params
            );

            SemanticQueryResponseV2 response = new SemanticQueryResponseV2(
                    meta,
                    (SemanticQueryAstV1) null,
                    compiledSql,
                    guardrail
            );

            Map<String, Object> outputPayload = new LinkedHashMap<>();
            outputPayload.put("tool", TOOL_CODE);
            outputPayload.put("version", VERSION);
            outputPayload.put("semantic_v2_stage", "query");
            outputPayload.put("semantic_v2_event", "output");
            outputPayload.put("question", safeQuestion);
            outputPayload.put("confidence", confidence);
            outputPayload.put("needsClarification", needsClarification);
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
            if (e.isBlank() || field == null || field.isBlank()) {
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
        CompiledSql compiled = new CompiledSql(sql, params == null ? Map.of() : params);
        List<SemanticSqlPolicyValidator> validators = validatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(validators);
        SemanticQueryContext context = new SemanticQueryContext(session == null ? "" : session.getUserText(), session);
        try {
            for (SemanticSqlPolicyValidator validator : validators) {
                if (validator == null || !validator.supports(context)) {
                    continue;
                }
                validator.validate(compiled, context);
            }
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
