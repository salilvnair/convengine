package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticAmbiguity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticAmbiguityOption;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticInterpretRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticInterpretResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticTimeRange;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
public class SemanticInterpretService {

    private static final String TOOL_CODE = "db.semantic.interpret";
    private static final String VERSION = "v2";

    private static final Pattern FENCED_JSON = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*?})\\s*```");
    private static final Pattern CUSTOMER_FOR_PATTERN = Pattern.compile("(?i)\\bfor\\s+([A-Za-z0-9_-]{2,40})\\b");
    private static final Pattern CUSTOMER_GENERIC_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9_-]{2,20})\\b");
    private static final Pattern FIELD_EQUALS_PATTERN = Pattern.compile("(?i)\\b(?:customer|customer_name|customerid|customer_id|team|assigned_team|status|feeder|feederid|feeder_id)\\s*=\\s*([A-Za-z0-9_-]{1,40})\\b");
    private static final Pattern TEAM_PATTERN = Pattern.compile("(?i)\\bteam\\s*([A-Za-z0-9_-]{1,20})\\b");

    private static final String OUTPUT_JSON_SCHEMA = """
            {
              "type":"object",
              "required":["canonicalIntent","confidence","needsClarification","clarificationQuestion","ambiguities","trace"],
              "properties":{
                "canonicalIntent":{
                  "type":"object",
                  "required":["intent","entity","queryClass","filters","timeRange","sort","limit"],
                  "properties":{
                    "intent":{"type":"string"},
                    "entity":{"type":"string"},
                    "queryClass":{"type":"string"},
                    "filters":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "required":["field","op","value"],
                        "properties":{
                          "field":{"type":"string"},
                          "op":{"type":"string"},
                          "value":{"type":["string","number","boolean","null"]}
                        },
                        "additionalProperties":false
                      }
                    },
                    "timeRange":{
                      "type":"object",
                      "required":["kind","value","timezone","from","to"],
                      "properties":{
                        "kind":{"type":["string","null"]},
                        "value":{"type":["string","null"]},
                        "timezone":{"type":["string","null"]},
                        "from":{"type":["string","null"]},
                        "to":{"type":["string","null"]}
                      },
                      "additionalProperties":false
                    },
                    "sort":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "required":["field","direction"],
                        "properties":{
                          "field":{"type":"string"},
                          "direction":{"type":"string"}
                        },
                        "additionalProperties":false
                      }
                    },
                    "limit":{"type":["integer","null"]}
                  },
                  "additionalProperties":false
                },
                "confidence":{"type":"number"},
                "needsClarification":{"type":"boolean"},
                "clarificationQuestion":{"type":["string","null"]},
                "ambiguities":{
                  "type":"array",
                  "items":{
                    "type":"object",
                    "required":["type","code","message","required","options"],
                    "properties":{
                      "type":{"type":"string"},
                      "code":{"type":"string"},
                      "message":{"type":"string"},
                      "required":{"type":"boolean"},
                      "options":{
                        "type":"array",
                        "items":{
                          "type":"object",
                          "required":["key","label","confidence"],
                          "properties":{
                            "key":{"type":"string"},
                            "label":{"type":"string"},
                            "confidence":{"type":"number"}
                          },
                          "additionalProperties":false
                        }
                      }
                    },
                    "additionalProperties":false
                  }
                },
                "trace":{
                  "type":"object",
                  "required":["normalizations","parser","sanitized","question","clarificationThreshold"],
                  "properties":{
                    "normalizations":{"type":"array","items":{"type":"string"}},
                    "parser":{"type":["string","null"]},
                    "sanitized":{"type":["boolean","null"]},
                    "question":{"type":["string","null"]},
                    "clarificationThreshold":{"type":["number","null"]}
                  },
                  "additionalProperties":false
                }
              },
              "additionalProperties":false
            }
            """;

    private final LlmClient llmClient;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final StaticConfigurationCacheService staticCacheService;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final SemanticModelRegistry semanticModelRegistry;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticInterpretResponse interpret(SemanticInterpretRequest request, EngineSession session) {
        String question = request == null || request.question() == null ? "" : request.question().trim();
        UUID conversationId = safeConversationId(session);

        Map<String, Object> inputAudit = new LinkedHashMap<>();
        inputAudit.put("tool", TOOL_CODE);
        inputAudit.put("version", VERSION);
        inputAudit.put("semantic_v2_stage", "interpret");
        inputAudit.put("semantic_v2_event", "input");
        inputAudit.put("question", question);
        inputAudit.put("hints", request == null ? Map.of() : safeMap(request.hints()));
        inputAudit.put("context", request == null ? Map.of() : safeMap(request.context()));
        audit("SEMANTIC_INTERPRET_INPUT", conversationId, inputAudit);
        verbose(session, "SEMANTIC_INTERPRET_INPUT", false, inputAudit);

        PromptPackage promptPackage = buildPromptPackage(question, request);
        String prompt = promptPackage.combinedPrompt();
        JsonNode llmNode = null;
        String llmRaw = null;
        Map<String, Object> llmInputAudit = new LinkedHashMap<>();
        llmInputAudit.put("tool", TOOL_CODE);
        llmInputAudit.put("version", VERSION);
        llmInputAudit.put("question", question);
        llmInputAudit.put("schema", OUTPUT_JSON_SCHEMA);
        llmInputAudit.put("system_prompt", promptPackage.systemPrompt());
        llmInputAudit.put("user_prompt", promptPackage.userPrompt());
        llmInputAudit.put("_meta", promptPackage.meta());
        audit("SEMANTIC_INTERPRET_LLM_INPUT", conversationId, llmInputAudit);
        verbose(session, "SEMANTIC_INTERPRET_LLM_INPUT", false, llmInputAudit);
        try {
            llmRaw = llmClient.generateJsonStrict(session, prompt, OUTPUT_JSON_SCHEMA,
                    session == null ? "{}" : session.getContextJson());
            llmNode = parseStrictOrLenient(llmRaw);
            Map<String, Object> llmOutputAudit = new LinkedHashMap<>();
            llmOutputAudit.put("tool", TOOL_CODE);
            llmOutputAudit.put("version", VERSION);
            llmOutputAudit.put("question", question);
            llmOutputAudit.put("json", llmRaw);
            audit("SEMANTIC_INTERPRET_LLM_OUTPUT", conversationId, llmOutputAudit);
            verbose(session, "SEMANTIC_INTERPRET_LLM_OUTPUT", false, llmOutputAudit);
        } catch (Exception ex) {
            Map<String, Object> errorAudit = new LinkedHashMap<>();
            errorAudit.put("tool", TOOL_CODE);
            errorAudit.put("errorClass", ex.getClass().getName());
            errorAudit.put("errorMessage", ex.getMessage());
            audit("SEMANTIC_INTERPRET_LLM_ERROR", conversationId, errorAudit);
            verbose(session, "SEMANTIC_INTERPRET_LLM_ERROR", true, errorAudit);
        }

        ParsedInterpret parsed = parseLlmResult(llmNode, request);
        if (parsed == null) {
            parsed = fallbackParse(question, request);
        }

        ParsedInterpret sanitized = sanitizeCanonicalIntent(parsed, question);
        ParsedInterpret finalized = finalizeClarification(sanitized, question, request);
        applyClarificationState(finalized, session);

        Map<String, Object> outputAudit = new LinkedHashMap<>();
        outputAudit.put("tool", TOOL_CODE);
        outputAudit.put("version", VERSION);
        outputAudit.put("semantic_v2_stage", "interpret");
        outputAudit.put("semantic_v2_event", "output");
        outputAudit.put("question", question);
        outputAudit.put("confidence", finalized.confidence());
        outputAudit.put("needsClarification", finalized.needsClarification());
        outputAudit.put("ambiguityCount", finalized.ambiguities() == null ? 0 : finalized.ambiguities().size());
        outputAudit.put("ambiguities", finalized.ambiguities());
        outputAudit.put("canonicalIntent", finalized.canonicalIntent());
        outputAudit.put("llmRaw", llmRaw);
        audit("SEMANTIC_INTERPRET_OUTPUT", conversationId, outputAudit);
        verbose(session, "SEMANTIC_INTERPRET_OUTPUT", false, outputAudit);

        SemanticToolMeta meta = new SemanticToolMeta(
                TOOL_CODE,
                VERSION,
                finalized.confidence(),
                finalized.needsClarification(),
                finalized.clarificationQuestion(),
                finalized.ambiguities()
        );

        return new SemanticInterpretResponse(meta, question, finalized.canonicalIntent(), finalized.trace());
    }

    private ParsedInterpret parseLlmResult(JsonNode node, SemanticInterpretRequest request) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode canonicalNode = node.path("canonicalIntent");
        if (canonicalNode.isMissingNode() || !canonicalNode.isObject()) {
            if (node.has("intent") || node.has("entity") || node.has("filters")) {
                canonicalNode = node;
            } else {
                return null;
            }
        }

        CanonicalIntent canonicalIntent = parseCanonicalIntent(canonicalNode, request);
        if (canonicalIntent == null) {
            return null;
        }
        double confidence = clamp01(number(node, "confidence", 0.0d));
        if (confidence <= 0.0d && node.path("meta").isObject()) {
            confidence = clamp01(number(node.path("meta"), "confidence", 0.0d));
        }
        if (confidence <= 0.0d) {
            confidence = scoreFromCanonical(canonicalIntent);
        }

        List<SemanticAmbiguity> ambiguities = parseAmbiguities(node.path("ambiguities"));
        if (ambiguities.isEmpty() && node.path("meta").isObject()) {
            ambiguities = parseAmbiguities(node.path("meta").path("ambiguities"));
        }

        boolean needsClarification = bool(node, "needsClarification") || hasRequiredAmbiguity(ambiguities);
        String clarificationQuestion = text(node, "clarificationQuestion");
        if ((clarificationQuestion == null || clarificationQuestion.isBlank()) && node.path("meta").isObject()) {
            clarificationQuestion = text(node.path("meta"), "clarificationQuestion");
        }

        Map<String, Object> trace = safeObject(node.path("trace"));
        if (trace.isEmpty()) {
            trace = new LinkedHashMap<>();
        }
        trace.putIfAbsent("parser", "llm");

        return new ParsedInterpret(canonicalIntent, confidence, needsClarification, clarificationQuestion, ambiguities, trace);
    }

    private ParsedInterpret sanitizeCanonicalIntent(ParsedInterpret parsed, String question) {
        CanonicalIntent intent = parsed.canonicalIntent();
        List<SemanticAmbiguity> ambiguities = new ArrayList<>(parsed.ambiguities());

        List<SemanticFilter> safeFilters = new ArrayList<>();
        for (SemanticFilter filter : intent.filters()) {
            if (filter == null || filter.field() == null || filter.field().isBlank()) {
                continue;
            }
            if (looksPhysicalName(filter.field())) {
                ambiguities.add(new SemanticAmbiguity(
                        "FIELD",
                        "field_requires_business_name",
                        "A filter field looked like a physical DB name and was removed.",
                        true,
                        List.of()
                ));
                continue;
            }
            safeFilters.add(new SemanticFilter(
                    normalizeField(filter.field()),
                    normalizeOp(filter.op()),
                    filter.value()
            ));
        }

        List<SemanticSort> safeSorts = new ArrayList<>();
        for (SemanticSort sort : intent.sort()) {
            if (sort == null || sort.field() == null || sort.field().isBlank()) {
                continue;
            }
            if (looksPhysicalName(sort.field())) {
                ambiguities.add(new SemanticAmbiguity(
                        "FIELD",
                        "sort_field_requires_business_name",
                        "A sort field looked like a physical DB name and was removed.",
                        true,
                        List.of()
                ));
                continue;
            }
            safeSorts.add(new SemanticSort(normalizeField(sort.field()), normalizeDirection(sort.direction())));
        }

        String safeEntity = intent.entity() == null || intent.entity().isBlank() ? "REQUEST" : intent.entity().trim().toUpperCase(Locale.ROOT);
        if (looksPhysicalName(safeEntity)) {
            safeEntity = "REQUEST";
            ambiguities.add(new SemanticAmbiguity(
                    "ENTITY",
                    "entity_requires_business_name",
                    "The entity looked like a physical table name.",
                    true,
                    List.of(new SemanticAmbiguityOption("REQUEST", "Request", 0.5d))
            ));
        }

        CanonicalIntent sanitizedIntent = new CanonicalIntent(
                normalizeIntent(intent.intent()),
                safeEntity,
                normalizeQueryClass(intent.queryClass()),
                safeFilters,
                sanitizeTimeRange(intent.timeRange()),
                safeSorts.isEmpty() ? List.of(new SemanticSort("created_at", "DESC")) : safeSorts,
                normalizeLimit(intent.limit())
        );

        double confidence = parsed.confidence();
        if (!ambiguities.isEmpty()) {
            confidence = Math.min(confidence, 0.74d);
        }

        Map<String, Object> trace = new LinkedHashMap<>(parsed.trace());
        trace.put("parser", trace.getOrDefault("parser", "llm"));
        trace.put("sanitized", true);
        trace.put("question", question);

        return new ParsedInterpret(sanitizedIntent, confidence, parsed.needsClarification(),
                parsed.clarificationQuestion(), normalizeAmbiguities(ambiguities), trace);
    }

    private ParsedInterpret finalizeClarification(ParsedInterpret parsed,
                                                  String question,
                                                  SemanticInterpretRequest request) {
        double threshold = clarificationThreshold();
        List<SemanticAmbiguity> ambiguities = normalizeAmbiguities(parsed.ambiguities());

        boolean needsClarification = parsed.needsClarification()
                || parsed.confidence() < threshold
                || hasRequiredAmbiguity(ambiguities);

        String clarificationQuestion = parsed.clarificationQuestion();
        if (needsClarification && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
            clarificationQuestion = buildClarificationQuestion(question, ambiguities, request);
        }

        Map<String, Object> trace = new LinkedHashMap<>(parsed.trace());
        trace.put("clarificationThreshold", threshold);

        return new ParsedInterpret(
                parsed.canonicalIntent(),
                parsed.confidence(),
                needsClarification,
                clarificationQuestion,
                ambiguities,
                trace
        );
    }

    private void applyClarificationState(ParsedInterpret parsed, EngineSession session) {
        if (session == null) {
            return;
        }
        if (!parsed.needsClarification()) {
            return;
        }
        if (parsed.clarificationQuestion() == null || parsed.clarificationQuestion().isBlank()) {
            return;
        }
        session.setPendingClarificationQuestion(parsed.clarificationQuestion());
        session.setPendingClarificationReason(ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
        session.addClarificationHistory();
    }

    private ParsedInterpret fallbackParse(String question, SemanticInterpretRequest request) {
        String normalized = question == null ? "" : question.trim();
        String q = normalized.toLowerCase(Locale.ROOT);

        List<SemanticFilter> filters = new ArrayList<>();
        List<String> normalizations = new ArrayList<>();
        List<SemanticAmbiguity> ambiguities = new ArrayList<>();

        if (containsAny(q, "disconnect", "terminate", "cancel line", "disconnected")) {
            filters.add(new SemanticFilter("request_type", "EQ", "DISCONNECT"));
            normalizations.add("disconnect*->DISCONNECT");
        } else if (containsAny(q, "relink", "re-link")) {
            filters.add(new SemanticFilter("request_type", "EQ", "RELINK"));
            normalizations.add("relink*->RELINK");
        }

        if (containsAny(q, "rejected", "reject", "declined")) {
            filters.add(new SemanticFilter("status", "EQ", "REJECTED"));
            normalizations.add("rejected*->REJECTED");
        } else if (containsAny(q, "failed", "error", "errored")) {
            filters.add(new SemanticFilter("status", "EQ", "FAILED"));
            normalizations.add("failed*->FAILED");
        } else if (containsAny(q, "approved")) {
            filters.add(new SemanticFilter("status", "EQ", "APPROVED"));
            normalizations.add("approved->APPROVED");
        } else if (containsAny(q, "pending")) {
            filters.add(new SemanticFilter("status", "EQ", "PENDING"));
            normalizations.add("pending->PENDING");
        }

        String customer = extractCustomer(normalized);
        if (customer != null && !customer.isBlank()) {
            filters.add(new SemanticFilter("customer", "EQ", customer));
        }

        SemanticTimeRange timeRange = parseTimeRange(q, request);
        if (timeRange != null) {
            normalizations.add("time->" + (timeRange.value() == null ? timeRange.kind() : timeRange.value()));
        }

        double confidence = 0.55d;
        if (!filters.isEmpty()) {
            confidence += 0.08d * Math.min(filters.size(), 3);
        }
        if (timeRange != null) {
            confidence += 0.10d;
        }
        confidence = clamp01(confidence);

        if (customer == null && normalized.matches(".*\\bfor\\b.*")) {
            ambiguities.add(new SemanticAmbiguity(
                    "FIELD",
                    "customer_or_account_unknown",
                    "The phrase after 'for' could map to customer, account, or contract.",
                    true,
                    List.of(
                            new SemanticAmbiguityOption("customer", "Customer", 0.40d),
                            new SemanticAmbiguityOption("account", "Account", 0.35d),
                            new SemanticAmbiguityOption("contract", "Contract", 0.25d)
                    )
            ));
            confidence = Math.min(confidence, 0.60d);
        }

        CanonicalIntent canonicalIntent = new CanonicalIntent(
                "LIST_REQUESTS",
                "REQUEST",
                "LIST_REQUESTS",
                filters,
                timeRange,
                List.of(new SemanticSort("created_at", "DESC")),
                normalizeLimit(null)
        );

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("parser", "fallback");
        trace.put("normalizations", normalizations);

        return new ParsedInterpret(
                canonicalIntent,
                confidence,
                false,
                null,
                ambiguities,
                trace
        );
    }

    private CanonicalIntent parseCanonicalIntent(JsonNode node, SemanticInterpretRequest request) {
        String intent = normalizeIntent(text(node, "intent"));
        String entity = text(node, "entity");
        if (entity == null || entity.isBlank()) {
            entity = text(node, "domain_object");
        }
        if (entity == null || entity.isBlank()) {
            entity = "REQUEST";
        }

        String queryClass = normalizeQueryClass(text(node, "queryClass"));

        List<SemanticFilter> filters = parseFilters(node.path("filters"));
        if (filters.isEmpty() && node.path("filter").isObject()) {
            filters = parseFilterObject(node.path("filter"));
        }

        SemanticTimeRange timeRange = parseTimeRange(node.path("timeRange"), request);
        if (timeRange == null && node.path("date_range").isObject()) {
            timeRange = parseTimeRange(node.path("date_range"), request);
        }

        List<SemanticSort> sort = parseSort(node.path("sort"));
        Integer limit = normalizeLimit(node.path("limit").isNumber() ? node.path("limit").asInt() : null);

        return new CanonicalIntent(intent, entity.trim().toUpperCase(Locale.ROOT), queryClass, filters, timeRange, sort, limit);
    }

    private List<SemanticFilter> parseFilters(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<SemanticFilter> out = new ArrayList<>();
            for (JsonNode item : node) {
                String field = text(item, "field");
                if (field == null || field.isBlank()) {
                    field = text(item, "name");
                }
                if (field == null || field.isBlank()) {
                    continue;
                }
                String op = normalizeOp(text(item, "op"));
                JsonNode valueNode = item.path("value");
                Object value = valueNode.isMissingNode() ? null : mapper.convertValue(valueNode, Object.class);
                out.add(new SemanticFilter(normalizeField(field), op, value));
            }
            return out;
        }
        if (node.isObject()) {
            return parseFilterObject(node);
        }
        return List.of();
    }

    private List<SemanticFilter> parseFilterObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<SemanticFilter> out = new ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() == null || entry.getValue().isNull()) {
                return;
            }
            out.add(new SemanticFilter(normalizeField(entry.getKey()), "EQ",
                    mapper.convertValue(entry.getValue(), Object.class)));
        });
        return out;
    }

    private List<SemanticSort> parseSort(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<SemanticSort> out = new ArrayList<>();
        for (JsonNode item : node) {
            String field = text(item, "field");
            if (field == null || field.isBlank()) {
                continue;
            }
            String direction = normalizeDirection(text(item, "direction"));
            out.add(new SemanticSort(normalizeField(field), direction));
        }
        return out;
    }

    private SemanticTimeRange parseTimeRange(JsonNode node, SemanticInterpretRequest request) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String kind = text(node, "kind");
        String value = text(node, "value");
        String timezone = text(node, "timezone");
        if (timezone == null || timezone.isBlank()) {
            timezone = requestTimezone(request);
        }
        String from = text(node, "from");
        String to = text(node, "to");
        if (kind == null && value != null) {
            kind = "RELATIVE";
        }
        if (kind == null && from != null) {
            kind = "ABSOLUTE";
        }
        if (kind == null) {
            return null;
        }
        return new SemanticTimeRange(kind.toUpperCase(Locale.ROOT),
                value == null ? null : value.toUpperCase(Locale.ROOT),
                timezone,
                from,
                to);
    }

    private SemanticTimeRange parseTimeRange(String normalizedQuestion, SemanticInterpretRequest request) {
        String timezone = requestTimezone(request);
        if (containsAny(normalizedQuestion, "today")) {
            return new SemanticTimeRange("RELATIVE", "TODAY", timezone, null, null);
        }
        if (containsAny(normalizedQuestion, "yesterday")) {
            return new SemanticTimeRange("RELATIVE", "YESTERDAY", timezone, null, null);
        }
        if (containsAny(normalizedQuestion, "last week", "previous week")) {
            return new SemanticTimeRange("RELATIVE", "LAST_WEEK", timezone, null, null);
        }
        if (containsAny(normalizedQuestion, "last month", "previous month")) {
            return new SemanticTimeRange("RELATIVE", "LAST_MONTH", timezone, null, null);
        }
        return null;
    }

    private SemanticTimeRange sanitizeTimeRange(SemanticTimeRange timeRange) {
        if (timeRange == null) {
            return null;
        }
        String kind = timeRange.kind() == null ? "RELATIVE" : timeRange.kind().trim().toUpperCase(Locale.ROOT);
        String timezone = timeRange.timezone();
        if (timezone == null || timezone.isBlank()) {
            timezone = defaultTimezone();
        }
        String value = timeRange.value() == null ? null : timeRange.value().trim().toUpperCase(Locale.ROOT);
        return new SemanticTimeRange(kind, value, timezone, timeRange.from(), timeRange.to());
    }

    private List<SemanticAmbiguity> parseAmbiguities(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SemanticAmbiguity> out = new ArrayList<>();
        for (JsonNode item : node) {
            String type = normalizeAmbiguityType(text(item, "type"));
            String code = defaultIfBlank(text(item, "code"), "unknown_ambiguity");
            String message = defaultIfBlank(text(item, "message"), "Ambiguity detected");
            boolean required = bool(item, "required");
            List<SemanticAmbiguityOption> options = parseAmbiguityOptions(item.path("options"));
            out.add(new SemanticAmbiguity(type, code, message, required, options));
        }
        return out;
    }

    private List<SemanticAmbiguityOption> parseAmbiguityOptions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SemanticAmbiguityOption> out = new ArrayList<>();
        for (JsonNode item : node) {
            String key = defaultIfBlank(text(item, "key"), "unknown");
            String label = defaultIfBlank(text(item, "label"), key);
            double confidence = clamp01(number(item, "confidence", 0.0d));
            out.add(new SemanticAmbiguityOption(key, label, confidence));
        }
        return out;
    }

    private List<SemanticAmbiguity> normalizeAmbiguities(List<SemanticAmbiguity> ambiguities) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return List.of();
        }
        List<SemanticAmbiguity> out = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity == null) {
                continue;
            }
            String key = (ambiguity.type() + ":" + ambiguity.code()).toUpperCase(Locale.ROOT);
            if (!dedupe.add(key)) {
                continue;
            }
            out.add(new SemanticAmbiguity(
                    normalizeAmbiguityType(ambiguity.type()),
                    defaultIfBlank(ambiguity.code(), "unknown_ambiguity"),
                    defaultIfBlank(ambiguity.message(), "Ambiguity detected"),
                    ambiguity.required() != null && ambiguity.required(),
                    ambiguity.options() == null ? List.of() : ambiguity.options()
            ));
        }
        return List.copyOf(out);
    }

    private PromptPackage buildPromptPackage(String question, SemanticInterpretRequest request) {
        PromptPackage dbPrompt = renderPromptFromTemplate(question, request);
        if (dbPrompt != null && dbPrompt.combinedPrompt() != null && !dbPrompt.combinedPrompt().isBlank()) {
            return dbPrompt;
        }

        String safeQuestion = defaultIfBlank(question, "");
        String timezone = requestTimezone(request);
        String nowDate = LocalDate.now(ZoneId.of(timezone)).toString();
        Map<String, Object> context = request == null ? Map.of() : safeMap(request.context());
        Map<String, Object> hints = request == null ? Map.of() : safeMap(request.hints());
        String queryClassKey = resolveQueryClassKey(request);
        String entityKeyHint = resolveEntityKeyHint(request);
        Map<String, Object> queryClassConfig = loadQueryClassConfig(queryClassKey);
        Map<String, Object> semanticAllowedValues = buildSemanticAllowedValues();
        Map<String, Object> semanticFields = buildSemanticFields();
        List<Map<String, Object>> ambiguityOptions = loadAmbiguityOptions(queryClassKey, entityKeyHint, safeQuestion, request);

        String systemPrompt = """
                You are a semantic interpreter for business analytics.
                Convert user text into canonical business intent JSON.

                Hard rules:
                - Return JSON only.
                - Never return SQL.
                - Never return table names, column names, or joins.
                - Use business-level fields only.
                - Keep confidence in range 0..1.
                - Set needsClarification=true when confidence is low or ambiguity exists.
                """;

        String userPrompt = """
                Current date: %s
                Timezone: %s

                User question:
                %s

                Hints:
                %s

                Context:
                %s

                Query class key:
                %s

                Query class defaults:
                %s

                Semantic fields:
                %s

                Semantic valid values:
                %s

                Ambiguity options (DB-driven):
                %s

                Expected shape:
                {
                  "canonicalIntent": {
                    "intent": "LIST_REQUESTS",
                    "entity": "REQUEST",
                    "queryClass": "LIST_REQUESTS",
                    "filters": [{"field":"status","op":"EQ","value":"REJECTED"}],
                    "timeRange": {"kind":"RELATIVE","value":"TODAY","timezone":"%s"},
                    "sort": [{"field":"created_at","direction":"DESC"}],
                    "limit": 100
                  },
                  "confidence": 0.0,
                  "needsClarification": false,
                  "clarificationQuestion": null,
                  "ambiguities": [],
                  "trace": {
                    "normalizations": []
                  }
                }
                """.formatted(
                nowDate,
                timezone,
                safeQuestion,
                safeJson(hints),
                safeJson(context),
                queryClassKey,
                safeJson(queryClassConfig),
                safeJson(semanticFields),
                safeJson(semanticAllowedValues),
                safeJson(ambiguityOptions),
                timezone
        );

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("promptSource", "fallback");
        meta.put("responseType", "SEMANTIC_INTERPRET");
        meta.put("intent", "SEMANTIC_QUERY");
        meta.put("state", "ANALYZE");
        meta.put("vars", Map.of(
                "question", safeQuestion,
                "current_date", nowDate,
                "current_timezone", timezone,
                "hints", hints,
                "semantic_context", context,
                "query_class_key", queryClassKey,
                "query_class_config", queryClassConfig,
                "semantic_fields", semanticFields,
                "semantic_allowed_values", semanticAllowedValues,
                "ambiguity_options", ambiguityOptions
        ));
        return new PromptPackage(systemPrompt, userPrompt, meta);
    }

    private PromptPackage renderPromptFromTemplate(String question, SemanticInterpretRequest request) {
        if (staticCacheService == null || promptTemplateRenderer == null) {
            return null;
        }
        String intent = "SEMANTIC_QUERY";
        String state = "ANALYZE";
        CePromptTemplate template = staticCacheService
                .findFirstPromptTemplate("SEMANTIC_INTERPRET", intent, state)
                .or(() -> staticCacheService.findFirstPromptTemplate("SEMANTIC_INTERPRET", intent))
                .orElse(null);
        if (template == null) {
            return null;
        }

        String timezone = requestTimezone(request);
        String nowDate = LocalDate.now(ZoneId.of(timezone)).toString();
        Map<String, Object> hints = request == null ? Map.of() : safeMap(request.hints());
        Map<String, Object> semanticContext = request == null ? Map.of() : safeMap(request.context());
        String queryClassKey = resolveQueryClassKey(request);
        String entityKeyHint = resolveEntityKeyHint(request);
        Map<String, Object> queryClassConfig = loadQueryClassConfig(queryClassKey);
        Map<String, Object> semanticAllowedValues = buildSemanticAllowedValues();
        Map<String, Object> semanticFields = buildSemanticFields();
        List<Map<String, Object>> ambiguityOptions = loadAmbiguityOptions(queryClassKey, entityKeyHint, defaultIfBlank(question, ""), request);

        PromptTemplateContext ctx = PromptTemplateContext.builder()
                .templateName("SemanticInterpretService")
                .systemPrompt(template.getSystemPrompt())
                .userPrompt(template.getUserPrompt())
                .context("{}")
                .userInput(defaultIfBlank(question, ""))
                .resolvedUserInput(defaultIfBlank(question, ""))
                .standaloneQuery(defaultIfBlank(question, ""))
                .question(defaultIfBlank(question, ""))
                .currentDate(nowDate)
                .currentTimezone(timezone)
                .extra(Map.of(
                        "hints", safeJson(hints),
                        "semantic_context", safeJson(semanticContext),
                        "query_class_key", queryClassKey,
                        "query_class_config", safeJson(queryClassConfig),
                        "semantic_fields", safeJson(semanticFields),
                        "semantic_allowed_values", safeJson(semanticAllowedValues),
                        "ambiguity_options", safeJson(ambiguityOptions)
                ))
                .build();

        String renderedSystem = promptTemplateRenderer.render(template.getSystemPrompt(), ctx);
        String renderedUser = promptTemplateRenderer.render(template.getUserPrompt(), ctx);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("promptSource", "ce_prompt_template");
        meta.put("templateId", template.getTemplateId());
        meta.put("responseType", "SEMANTIC_INTERPRET");
        meta.put("intent", intent);
        meta.put("state", state);
        meta.put("vars", Map.of(
                "question", defaultIfBlank(question, ""),
                "current_date", nowDate,
                "current_timezone", timezone,
                "hints", hints,
                "semantic_context", semanticContext,
                "query_class_key", queryClassKey,
                "query_class_config", queryClassConfig,
                "semantic_fields", semanticFields,
                "semantic_allowed_values", semanticAllowedValues,
                "ambiguity_options", ambiguityOptions
        ));
        return new PromptPackage(renderedSystem, renderedUser, meta);
    }

    private String resolveQueryClassKey(SemanticInterpretRequest request) {
        if (request != null && request.hints() != null) {
            Object queryClass = request.hints().get("queryClass");
            if (queryClass == null) {
                queryClass = request.hints().get("query_class");
            }
            if (queryClass != null && !String.valueOf(queryClass).isBlank()) {
                return String.valueOf(queryClass).trim().toUpperCase(Locale.ROOT);
            }
        }
        return "LIST_REQUESTS";
    }

    private String resolveEntityKeyHint(SemanticInterpretRequest request) {
        if (request != null && request.hints() != null) {
            Object value = firstNonNull(
                    request.hints().get("entity"),
                    request.hints().get("entity_key"),
                    request.hints().get("entityKey"),
                    request.hints().get("canonicalEntity")
            );
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            }
        }
        if (request != null && request.context() != null) {
            Object value = firstNonNull(
                    request.context().get("entity"),
                    request.context().get("entity_key"),
                    request.context().get("entityKey"),
                    request.context().get("canonicalEntity")
            );
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> loadQueryClassConfig(String queryClassKey) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || queryClassKey == null || queryClassKey.isBlank()) {
            return Map.of();
        }
        String sql = """
                SELECT query_class_key, base_table_name, allowed_filter_fields_json, default_select_fields_json, default_sort_fields_json
                FROM ce_semantic_query_class
                WHERE enabled = true
                  AND UPPER(query_class_key) = UPPER(:queryClassKey)
                ORDER BY COALESCE(priority, 999999)
                LIMIT 1
                """;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("queryClassKey", queryClassKey));
            if (rows.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> row = rows.getFirst();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("queryClassKey", asText(row.get("query_class_key")));
            out.put("baseTableName", asText(row.get("base_table_name")));
            out.put("allowedFilterFields", parseJsonArray(row.get("allowed_filter_fields_json")));
            out.put("defaultSelectFields", parseJsonArray(row.get("default_select_fields_json")));
            out.put("defaultSortFields", parseJsonArray(row.get("default_sort_fields_json")));
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> loadAmbiguityOptions(String queryClassKey,
                                                           String entityKeyHint,
                                                           String question,
                                                           SemanticInterpretRequest request) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || queryClassKey == null || queryClassKey.isBlank()) {
            return List.of();
        }
        String placeholderValue = resolvePlaceholderValue(question, request);
        String sql = """
                SELECT entity_key, query_class_key, ambiguity_code, field_key, option_key, option_label,
                       mapped_filter_json, recommended, priority
                FROM ce_semantic_ambiguity_option
                WHERE enabled = true
                  AND UPPER(query_class_key) = UPPER(:queryClassKey)
                """;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("queryClassKey", queryClassKey);
        if (entityKeyHint != null && !entityKeyHint.isBlank()) {
            sql += """
                    AND UPPER(entity_key) = UPPER(:entityKey)
                    """;
            params.put("entityKey", entityKeyHint);
        }
        sql += """
                ORDER BY COALESCE(priority, 999999), option_label
                """;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("entityKey", asText(row.get("entity_key")));
                option.put("queryClassKey", asText(row.get("query_class_key")));
                option.put("ambiguityCode", asText(row.get("ambiguity_code")));
                option.put("fieldKey", asText(row.get("field_key")));
                option.put("key", asText(row.get("option_key")));
                option.put("label", replacePlaceholder(asText(row.get("option_label")), placeholderValue));
                option.put("recommended", boolObject(row.get("recommended")));
                option.put("priority", toInt(row.get("priority"), 999999));
                option.put("mappedFilter", replacePlaceholderInObject(parseJsonObject(row.get("mapped_filter_json")), placeholderValue));
                out.add(option);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String resolvePlaceholderValue(String question, SemanticInterpretRequest request) {
        if (request != null) {
            Map<String, Object> hints = safeMap(request.hints());
            Map<String, Object> context = safeMap(request.context());
            Object direct = firstNonNull(
                    hints.get("value"),
                    hints.get("filter_value"),
                    hints.get("customer"),
                    hints.get("customerName"),
                    hints.get("customer_name"),
                    hints.get("team"),
                    hints.get("assignedTeam"),
                    hints.get("assigned_team"),
                    context.get("value"),
                    context.get("filter_value"),
                    context.get("customer"),
                    context.get("customerName"),
                    context.get("customer_name"),
                    context.get("team"),
                    context.get("assignedTeam"),
                    context.get("assigned_team")
            );
            if (direct != null && !String.valueOf(direct).isBlank()) {
                return String.valueOf(direct).trim();
            }
        }
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher fieldEquals = FIELD_EQUALS_PATTERN.matcher(question);
        if (fieldEquals.find()) {
            return fieldEquals.group(1).trim();
        }
        Matcher team = TEAM_PATTERN.matcher(question);
        if (team.find()) {
            String token = team.group(1).trim();
            if (token.matches("\\d+")) {
                return "Team" + token;
            }
            if (!token.isBlank()) {
                return token;
            }
        }
        return extractCustomer(question);
    }

    private Object replacePlaceholderInObject(Object value, String replacement) {
        if (replacement == null || replacement.isBlank() || value == null) {
            return value;
        }
        if (value instanceof String stringValue) {
            return replacePlaceholder(stringValue, replacement);
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> out = new LinkedHashMap<>();
            mapValue.forEach((k, v) -> out.put(String.valueOf(k), replacePlaceholderInObject(v, replacement)));
            return out;
        }
        if (value instanceof List<?> listValue) {
            List<Object> out = new ArrayList<>();
            for (Object item : listValue) {
                out.add(replacePlaceholderInObject(item, replacement));
            }
            return out;
        }
        return value;
    }

    private String replacePlaceholder(String value, String replacement) {
        if (value == null || replacement == null || replacement.isBlank()) {
            return value;
        }
        return value
                .replace("{{value}}", replacement)
                .replace("{{ value }}", replacement)
                .replace("{{  value  }}", replacement);
    }

    private List<Object> parseJsonArray(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        try {
            String raw = String.valueOf(value).trim();
            if (raw.isBlank()) {
                return List.of();
            }
            if (raw.startsWith("[") && raw.endsWith("]")) {
                return mapper.readValue(raw, new TypeReference<List<Object>>() {});
            }
            return List.of(raw);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        try {
            String raw = String.valueOf(value).trim();
            if (raw.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private boolean boolObject(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "y".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private Map<String, Object> buildSemanticAllowedValues() {
        SemanticModel model = semanticModelRegistry == null ? null : semanticModelRegistry.getModel();
        if (model == null || model.entities() == null || model.entities().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticEntity> entityEntry : model.entities().entrySet()) {
            String entityName = entityEntry.getKey();
            SemanticEntity entity = entityEntry.getValue();
            if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
                continue;
            }
            Map<String, Object> fieldValues = new LinkedHashMap<>();
            for (Map.Entry<String, SemanticField> fieldEntry : entity.fields().entrySet()) {
                String fieldName = fieldEntry.getKey();
                SemanticField field = fieldEntry.getValue();
                if (field == null || field.allowedValues() == null || field.allowedValues().isEmpty()) {
                    continue;
                }
                fieldValues.put(fieldName, field.allowedValues());
            }
            if (!fieldValues.isEmpty()) {
                out.put(entityName, fieldValues);
            }
        }
        return out;
    }

    private Map<String, Object> buildSemanticFields() {
        SemanticModel model = semanticModelRegistry == null ? null : semanticModelRegistry.getModel();
        if (model == null || model.entities() == null || model.entities().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticEntity> entityEntry : model.entities().entrySet()) {
            String entityName = entityEntry.getKey();
            SemanticEntity entity = entityEntry.getValue();
            if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map.Entry<String, SemanticField> fieldEntry : entity.fields().entrySet()) {
                String fieldName = fieldEntry.getKey();
                SemanticField field = fieldEntry.getValue();
                if (field == null) {
                    continue;
                }
                Map<String, Object> fieldMap = new LinkedHashMap<>();
                fieldMap.put("field", fieldName);
                fieldMap.put("type", field.type());
                fieldMap.put("aliases", field.aliases() == null ? List.of() : field.aliases());
                fieldMap.put("filterable", field.filterable());
                fieldMap.put("searchable", field.searchable());
                fields.add(fieldMap);
            }
            if (!fields.isEmpty()) {
                out.put(entityName, fields);
            }
        }
        return out;
    }

    private String buildClarificationQuestion(String question,
                                              List<SemanticAmbiguity> ambiguities,
                                              SemanticInterpretRequest request) {
        if (ambiguities != null) {
            for (SemanticAmbiguity ambiguity : ambiguities) {
                if (ambiguity == null || ambiguity.options() == null || ambiguity.options().isEmpty()) {
                    continue;
                }
                if (ambiguity.options().size() == 1) {
                    continue;
                }
                StringBuilder options = new StringBuilder();
                int idx = 1;
                for (SemanticAmbiguityOption option : ambiguity.options()) {
                    if (option == null || option.label() == null || option.label().isBlank()) {
                        continue;
                    }
                    if (!options.isEmpty()) {
                        options.append(" ");
                    }
                    options.append(idx).append(". ").append(option.label()).append(";");
                    idx++;
                }
                if (!options.isEmpty()) {
                    return "I found multiple interpretations. Which one is correct? " + options;
                }
            }
        }

        String timezone = requestTimezone(request);
        return "I need one clarification to avoid a wrong query. Can you specify the exact entity/filter for: \""
                + defaultIfBlank(question, "your request") + "\" (timezone " + timezone + ")?";
    }

    private JsonNode parseStrictOrLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode strict = mapper.readTree(raw);
            if (strict != null && strict.isObject()) {
                return strict;
            }
        } catch (Exception ignored) {
            // continue to lenient parse
        }

        String candidate = extractFencedJson(raw);
        if (candidate == null || candidate.isBlank()) {
            candidate = extractFirstJsonObject(raw);
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = mapper.readTree(candidate);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractFencedJson(String raw) {
        Matcher matcher = FENCED_JSON.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractFirstJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private double scoreFromCanonical(CanonicalIntent intent) {
        if (intent == null) {
            return 0.0d;
        }
        double score = 0.45d;
        if (intent.intent() != null && !intent.intent().isBlank()) {
            score += 0.15d;
        }
        if (intent.entity() != null && !intent.entity().isBlank()) {
            score += 0.10d;
        }
        if (intent.filters() != null && !intent.filters().isEmpty()) {
            score += Math.min(0.20d, 0.06d * intent.filters().size());
        }
        if (intent.timeRange() != null) {
            score += 0.08d;
        }
        return clamp01(score);
    }

    private String extractCustomer(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher forMatcher = CUSTOMER_FOR_PATTERN.matcher(question);
        if (forMatcher.find()) {
            return forMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher tokenMatcher = CUSTOMER_GENERIC_PATTERN.matcher(question);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1);
            if (token == null || token.length() < 3) {
                continue;
            }
            String upper = token.toUpperCase(Locale.ROOT);
            if ("SHOW".equals(upper) || "LIST".equals(upper) || "TODAY".equals(upper)
                    || "YESTERDAY".equals(upper) || "FAILED".equals(upper) || "REJECTED".equals(upper)) {
                continue;
            }
            return upper;
        }
        return null;
    }

    private boolean looksPhysicalName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.contains(".")
                || v.contains(" table")
                || v.contains(" column");
    }

    private String normalizeIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return "LIST_REQUESTS";
        }
        String normalized = intent.trim().toUpperCase(Locale.ROOT);
        if ("LIST".equals(normalized) || "FIND".equals(normalized) || "SHOW".equals(normalized)) {
            return "LIST_REQUESTS";
        }
        return normalized;
    }

    private String normalizeQueryClass(String queryClass) {
        if (queryClass == null || queryClass.isBlank()) {
            return "LIST_REQUESTS";
        }
        String normalized = queryClass.trim().toUpperCase(Locale.ROOT);
        if ("FILTER".equals(normalized) || "LIST".equals(normalized) || "FIND".equals(normalized) || "SHOW".equals(normalized)) {
            return "LIST_REQUESTS";
        }
        return normalized;
    }

    private String normalizeField(String field) {
        if (field == null || field.isBlank()) {
            return "unknown";
        }
        String normalized = field.trim();
        if (normalized.contains(".")) {
            normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        return normalized;
    }

    private String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            return "EQ";
        }
        String normalized = op.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "=", "EQ", "EQUALS" -> "EQ";
            case "!=", "NE", "NOT_EQUALS" -> "NE";
            case "IN" -> "IN";
            case "NOT_IN" -> "NOT_IN";
            case "LIKE" -> "LIKE";
            case "ILIKE" -> "ILIKE";
            case ">", "GT" -> "GT";
            case ">=", "GTE" -> "GTE";
            case "<", "LT" -> "LT";
            case "<=", "LTE" -> "LTE";
            case "BETWEEN" -> "BETWEEN";
            default -> "EQ";
        };
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "DESC";
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        return "ASC".equals(normalized) ? "ASC" : "DESC";
    }

    private String normalizeAmbiguityType(String type) {
        if (type == null || type.isBlank()) {
            return "FIELD";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ENTITY", "FIELD", "VALUE", "TIME_RANGE", "JOIN_PATH", "QUERY_CLASS" -> normalized;
            default -> "FIELD";
        };
    }

    private Integer normalizeLimit(Integer inputLimit) {
        int fallback = defaultLimit();
        int maxLimit = maxLimit();
        int limit = inputLimit == null ? fallback : inputLimit;
        if (limit <= 0) {
            limit = fallback;
        }
        return Math.min(limit, maxLimit);
    }

    private boolean hasRequiredAmbiguity(List<SemanticAmbiguity> ambiguities) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return false;
        }
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity != null && ambiguity.required() != null && ambiguity.required()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... patterns) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String requestTimezone(SemanticInterpretRequest request) {
        if (request != null && request.hints() != null) {
            Object timezone = request.hints().get("timezone");
            if (timezone != null && !String.valueOf(timezone).isBlank()) {
                return String.valueOf(timezone).trim();
            }
        }
        return defaultTimezone();
    }

    private String defaultTimezone() {
        ConvEngineMcpConfig.Db.Semantic semantic = semanticCfg();
        return semantic.getTimezone() == null || semantic.getTimezone().isBlank()
                ? "UTC"
                : semantic.getTimezone().trim();
    }

    private int defaultLimit() {
        return Math.max(1, semanticCfg().getDefaultLimit());
    }

    private int maxLimit() {
        return Math.max(defaultLimit(), semanticCfg().getMaxLimit());
    }

    private double clarificationThreshold() {
        ConvEngineMcpConfig.Db.Semantic.Clarification cfg = semanticCfg().getClarification();
        if (cfg == null) {
            return 0.80d;
        }
        return clamp01(cfg.getConfidenceThreshold());
    }

    private ConvEngineMcpConfig.Db.Semantic semanticCfg() {
        if (mcpConfig == null || mcpConfig.getDb() == null || mcpConfig.getDb().getSemantic() == null) {
            return new ConvEngineMcpConfig.Db.Semantic();
        }
        return mcpConfig.getDb().getSemantic();
    }

    private void audit(String stage, UUID conversationId, Map<String, Object> payload) {
        if (conversationId == null) {
            return;
        }
        auditService.audit(stage, conversationId, payload == null ? Map.of() : payload);
    }

    private void verbose(EngineSession session, String determinant, boolean error, Map<String, Object> payload) {
        if (session == null || verbosePublisher == null) {
            return;
        }
        verbosePublisher.publish(session,
                "SemanticInterpretService",
                determinant,
                null,
                TOOL_CODE,
                error,
                payload == null ? Map.of() : payload);
    }

    private UUID safeConversationId(EngineSession session) {
        return session == null ? null : session.getConversationId();
    }

    private Map<String, Object> safeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(map);
    }

    private Map<String, Object> safeObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<>() {
        });
    }

    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode child = node.path(field);
        return child.isTextual() ? child.asText() : null;
    }

    private boolean bool(JsonNode node, String field) {
        if (node == null || field == null) {
            return false;
        }
        return node.path(field).asBoolean(false);
    }

    private double number(JsonNode node, String field, double fallback) {
        if (node == null || field == null) {
            return fallback;
        }
        JsonNode child = node.path(field);
        if (child.isNumber()) {
            return child.asDouble(fallback);
        }
        if (child.isTextual()) {
            try {
                return Double.parseDouble(child.asText().trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return 0.0d;
        }
        if (x < 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, x);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record ParsedInterpret(
            CanonicalIntent canonicalIntent,
            double confidence,
            boolean needsClarification,
            String clarificationQuestion,
            List<SemanticAmbiguity> ambiguities,
            Map<String, Object> trace
    ) {
    }

    private record PromptPackage(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> meta
    ) {
        String combinedPrompt() {
            return (systemPrompt == null ? "" : systemPrompt) + "\n\n" + (userPrompt == null ? "" : userPrompt);
        }
    }
}
