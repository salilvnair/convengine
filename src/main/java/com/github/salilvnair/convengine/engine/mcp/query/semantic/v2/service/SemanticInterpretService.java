package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
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
import com.github.salilvnair.convengine.entity.CeOutputSchema;
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
import java.util.Optional;
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
    private static final Pattern GENERIC_FOR_PATTERN = Pattern.compile("(?i)\\bfor\\s+([A-Za-z0-9_-]{2,40})\\b");
    private static final Pattern GENERIC_FIELD_FILTER_PATTERN = Pattern.compile("(?i)\\b([a-z][a-z0-9_]{1,40})\\b\\s*(?:=|is|eq|equals?)\\s*['\"]?([A-Za-z0-9_-]{1,60})['\"]?");
    private static final Pattern VALUE_AFTER_OPERATOR_PATTERN = Pattern.compile("(?i)\\b[\\w.]+\\s*(?:=|is)\\s*['\"]?([A-Za-z0-9_-]{2,40})['\"]?");
    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile("['\"]([A-Za-z0-9_-]{2,40})['\"]");
    private static final Pattern GENERIC_TOKEN_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9_-]{2,20})\\b");

    private static final String OUTPUT_JSON_SCHEMA = """
            {
              "type":"object",
              "required":["canonicalIntent","confidence","needsClarification","clarificationQuestion","placeholderValue","clarificationResolved","selectedOptionKey","clarificationAnswerText","ambiguities","trace"],
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
                "placeholderValue":{"type":["string","null"]},
                "clarificationResolved":{"type":"boolean"},
                "selectedOptionKey":{"type":["string","null"]},
                "clarificationAnswerText":{"type":["string","null"]},
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

        ParsedInterpret clarificationSelected = resolveClarificationSelection(question, request);
        if (clarificationSelected != null) {
            ParsedInterpret sanitizedSelected = sanitizeCanonicalIntent(clarificationSelected, question);
            ParsedInterpret finalizedSelected = finalizeClarification(sanitizedSelected, question, request);
            applyClarificationState(finalizedSelected, session);

            Map<String, Object> selectedAudit = new LinkedHashMap<>();
            selectedAudit.put("tool", TOOL_CODE);
            selectedAudit.put("version", VERSION);
            selectedAudit.put("semantic_v2_stage", "interpret");
            selectedAudit.put("semantic_v2_event", "clarification_selection");
            selectedAudit.put("question", question);
            selectedAudit.put("confidence", finalizedSelected.confidence());
            selectedAudit.put("needsClarification", finalizedSelected.needsClarification());
            boolean selectedUnsupported = hasUnsupportedAmbiguity(finalizedSelected.ambiguities());
            String selectedUnsupportedMessage = selectedUnsupported ? unsupportedAmbiguityMessage(finalizedSelected.ambiguities()) : null;
            selectedAudit.put("unsupported", selectedUnsupported);
            selectedAudit.put("unsupportedMessage", selectedUnsupportedMessage);
            selectedAudit.put("ambiguityCount", finalizedSelected.ambiguities() == null ? 0 : finalizedSelected.ambiguities().size());
            selectedAudit.put("canonicalIntent", finalizedSelected.canonicalIntent());
            selectedAudit.put("trace", finalizedSelected.trace());
            audit("SEMANTIC_INTERPRET_SELECTION_RESOLVED", conversationId, selectedAudit);
            verbose(session, "SEMANTIC_INTERPRET_SELECTION_RESOLVED", false, selectedAudit);

            SemanticToolMeta meta = new SemanticToolMeta(
                    TOOL_CODE,
                    VERSION,
                    finalizedSelected.confidence(),
                    finalizedSelected.needsClarification(),
                    finalizedSelected.clarificationQuestion(),
                    finalizedSelected.ambiguities(),
                    selectedUnsupported,
                    selectedUnsupportedMessage
            );
            return new SemanticInterpretResponse(meta, question, finalizedSelected.canonicalIntent(), finalizedSelected.trace());
        }

        PromptPackage promptPackage = buildPromptPackage(question, request, conversationId, session);
        String prompt = promptPackage.combinedPrompt();
        SchemaPackage schemaPackage = resolveOutputJsonSchema();
        JsonNode llmNode = null;
        String llmRaw = null;
        Map<String, Object> llmInputAudit = new LinkedHashMap<>();
        llmInputAudit.put("tool", TOOL_CODE);
        llmInputAudit.put("version", VERSION);
        llmInputAudit.put("question", question);
        llmInputAudit.put("schema", schemaPackage.schema());
        llmInputAudit.put("schema_source", schemaPackage.source());
        llmInputAudit.put("schema_id", schemaPackage.schemaId());
        llmInputAudit.put("system_prompt", promptPackage.systemPrompt());
        llmInputAudit.put("user_prompt", promptPackage.userPrompt());
        llmInputAudit.put("resolved_prompt", prompt);
        if (promptPackage.meta() != null) {
            llmInputAudit.put("prompt_vars", promptPackage.meta().get("vars"));
        }
        llmInputAudit.put("_meta", promptPackage.meta());
        audit("SEMANTIC_INTERPRET_LLM_INPUT", conversationId, llmInputAudit);
        verbose(session, "SEMANTIC_INTERPRET_LLM_INPUT", false, llmInputAudit);
        try {
            llmRaw = llmClient.generateJsonStrict(session, prompt, schemaPackage.schema(),
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
        parsed = applyClarificationContract(parsed);

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
        boolean unsupported = hasUnsupportedAmbiguity(finalized.ambiguities());
        String unsupportedMessage = unsupported ? unsupportedAmbiguityMessage(finalized.ambiguities()) : null;
        outputAudit.put("unsupported", unsupported);
        outputAudit.put("unsupportedMessage", unsupportedMessage);
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
                finalized.ambiguities(),
                unsupported,
                unsupportedMessage
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
        String placeholderValue = text(node, "placeholderValue");
        if ((placeholderValue == null || placeholderValue.isBlank()) && node.path("meta").isObject()) {
            placeholderValue = text(node.path("meta"), "placeholderValue");
        }
        boolean clarificationResolved = bool(node, "clarificationResolved");
        if (!clarificationResolved && node.path("meta").isObject()) {
            clarificationResolved = bool(node.path("meta"), "clarificationResolved");
        }
        String selectedOptionKey = text(node, "selectedOptionKey");
        if ((selectedOptionKey == null || selectedOptionKey.isBlank()) && node.path("meta").isObject()) {
            selectedOptionKey = text(node.path("meta"), "selectedOptionKey");
        }
        String clarificationAnswerText = text(node, "clarificationAnswerText");
        if ((clarificationAnswerText == null || clarificationAnswerText.isBlank()) && node.path("meta").isObject()) {
            clarificationAnswerText = text(node.path("meta"), "clarificationAnswerText");
        }

        Map<String, Object> trace = safeObject(node.path("trace"));
        if (trace.isEmpty()) {
            trace = new LinkedHashMap<>();
        }
        trace.putIfAbsent("parser", "llm");

        return new ParsedInterpret(canonicalIntent, confidence, needsClarification, clarificationQuestion, ambiguities, trace,
                placeholderValue, clarificationResolved, selectedOptionKey, clarificationAnswerText);
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

        // Deterministic explicit-field override: if user explicitly provides a business field, trust it.
        if (safeFilters.isEmpty() && question != null && !question.isBlank()) {
            SemanticFilter explicit = extractExplicitBusinessFieldFilter(question);
            if (explicit != null) {
                safeFilters.add(explicit);
                ambiguities.removeIf(a -> a != null
                        && "FIELD".equalsIgnoreCase(a.type())
                        && a.code() != null
                        && a.code().toLowerCase(Locale.ROOT).contains("ambiguous"));
            }
        }

        String safeEntity = normalizeEntityKey(intent.entity());
        List<String> allowedEntities = loadAllowedEntityKeys();
        if (!allowedEntities.isEmpty() && !allowedEntities.contains(safeEntity)) {
            List<SemanticAmbiguityOption> options = new ArrayList<>();
            for (String key : allowedEntities.stream().limit(5).toList()) {
                options.add(new SemanticAmbiguityOption(key, key, 0.5d));
            }
            ambiguities.add(new SemanticAmbiguity(
                    "ENTITY",
                    "entity_not_allowed",
                    "Entity is not in allowed_entity_keys.",
                    true,
                    options
            ));
            if (allowedEntities.size() == 1) {
                safeEntity = allowedEntities.getFirst();
            }
        }

        Set<String> allowedFields = allowedFieldSetForEntity(safeEntity);
        if (!allowedFields.isEmpty()) {
            List<SemanticFilter> filtered = new ArrayList<>();
            for (SemanticFilter filter : safeFilters) {
                if (filter == null || filter.field() == null) {
                    continue;
                }
                if (!allowedFields.contains(filter.field().toLowerCase(Locale.ROOT))) {
                    ambiguities.add(new SemanticAmbiguity(
                            "FIELD",
                            "field_not_allowed",
                            "Filter field is not in allowed_fields_by_entity for " + safeEntity,
                            true,
                            List.of()
                    ));
                    continue;
                }
                filtered.add(filter);
            }
            safeFilters = filtered;

            List<SemanticSort> sorted = new ArrayList<>();
            for (SemanticSort sort : safeSorts) {
                if (sort == null || sort.field() == null) {
                    continue;
                }
                if (!allowedFields.contains(sort.field().toLowerCase(Locale.ROOT))) {
                    ambiguities.add(new SemanticAmbiguity(
                            "FIELD",
                            "sort_not_allowed",
                            "Sort field is not in allowed_fields_by_entity for " + safeEntity,
                            true,
                            List.of()
                    ));
                    continue;
                }
                sorted.add(sort);
            }
            safeSorts = sorted;
        }

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
        } else if (!safeFilters.isEmpty()) {
            confidence = Math.max(confidence, 0.90d);
        }

        Map<String, Object> trace = new LinkedHashMap<>(parsed.trace());
        trace.put("parser", trace.getOrDefault("parser", "llm"));
        trace.put("sanitized", true);
        trace.put("question", question);
        if (parsed.placeholderValue() != null && !parsed.placeholderValue().isBlank()) {
            trace.put("placeholderValue", parsed.placeholderValue());
        }

        return new ParsedInterpret(sanitizedIntent, confidence, parsed.needsClarification(),
                parsed.clarificationQuestion(), normalizeAmbiguities(ambiguities), trace, parsed.placeholderValue(),
                parsed.clarificationResolved(), parsed.selectedOptionKey(), parsed.clarificationAnswerText());
    }

    private ParsedInterpret finalizeClarification(ParsedInterpret parsed,
                                                  String question,
                                                  SemanticInterpretRequest request) {
        double threshold = clarificationThreshold();
        List<SemanticAmbiguity> ambiguities = normalizeAmbiguities(parsed.ambiguities());

        boolean needsClarification = parsed.needsClarification()
                || parsed.confidence() < threshold
                || hasRequiredAmbiguity(ambiguities);
        boolean unsupported = hasUnsupportedAmbiguity(ambiguities);

        String placeholderValue = parsed.placeholderValue();
        if (placeholderValue == null || placeholderValue.isBlank()) {
            Object fromTrace = parsed.trace() == null ? null : parsed.trace().get("placeholderValue");
            if (fromTrace != null && !String.valueOf(fromTrace).isBlank()) {
                placeholderValue = String.valueOf(fromTrace).trim();
            }
        }
        ambiguities = applyPlaceholderToAmbiguities(ambiguities, placeholderValue);
        String clarificationQuestion = replacePlaceholderToken(parsed.clarificationQuestion(), placeholderValue);
        if (unsupported) {
            needsClarification = false;
            clarificationQuestion = unsupportedAmbiguityMessage(ambiguities);
        }
        if (needsClarification && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
            clarificationQuestion = buildClarificationQuestion(question, ambiguities, request);
        }

        Map<String, Object> trace = new LinkedHashMap<>(parsed.trace());
        trace.put("clarificationThreshold", threshold);
        if (placeholderValue != null && !placeholderValue.isBlank()) {
            trace.put("placeholderValue", placeholderValue);
        }

        return new ParsedInterpret(
                parsed.canonicalIntent(),
                parsed.confidence(),
                needsClarification,
                clarificationQuestion,
                ambiguities,
                trace,
                placeholderValue,
                parsed.clarificationResolved(),
                parsed.selectedOptionKey(),
                parsed.clarificationAnswerText()
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

        Matcher filterMatcher = GENERIC_FIELD_FILTER_PATTERN.matcher(normalized);
        while (filterMatcher.find()) {
            String field = normalizeField(filterMatcher.group(1));
            String value = filterMatcher.group(2);
            if (field == null || field.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            filters.add(new SemanticFilter(field, "EQ", value));
            normalizations.add("filter:" + field + "=<value>");
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

        if (normalized.matches(".*\\bfor\\b.*")) {
            ambiguities.add(new SemanticAmbiguity(
                    "FIELD",
                    "identifier_unknown",
                    "The value after 'for' could map to multiple business identifiers.",
                    true,
                    List.of(
                            new SemanticAmbiguityOption("name", "Name", 0.40d),
                            new SemanticAmbiguityOption("code", "Code", 0.35d),
                            new SemanticAmbiguityOption("id", "Identifier", 0.25d)
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
                trace,
                null,
                false,
                null,
                null
        );
    }

    private ParsedInterpret resolveClarificationSelection(String question, SemanticInterpretRequest request) {
        Integer selectedIndex = parseSelectionNumber(question);
        if (selectedIndex == null) {
            return null;
        }
        Map<String, Object> context = request == null ? Map.of() : safeMap(request.context());
        if (context.isEmpty()) {
            return null;
        }
        Map<String, Object> mcp = safeMap(context.get("mcp"));
        List<Map<String, Object>> observations = safeMapList(mcp.get("observations"));
        if (observations.isEmpty()) {
            return null;
        }
        Map<String, Object> obs = lastObservationByTool(observations, TOOL_CODE);
        if (obs.isEmpty()) {
            return null;
        }
        Map<String, Object> json = safeMap(obs.get("json"));
        Map<String, Object> canonical = safeMap(json.get("canonicalIntent"));
        if (canonical.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> ambiguities = safeMapList(safeMap(json.get("meta")).get("ambiguities"));
        if (ambiguities.isEmpty()) {
            ambiguities = safeMapList(json.get("ambiguities"));
        }
        if (ambiguities.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> options = safeMapList(ambiguities.getFirst().get("options"));
        if (options.isEmpty() || selectedIndex < 1 || selectedIndex > options.size()) {
            return null;
        }
        Map<String, Object> selectedOption = options.get(selectedIndex - 1);
        String optionKey = asText(selectedOption.get("key"));
        if (optionKey == null || optionKey.isBlank()) {
            return null;
        }

        String queryClass = defaultIfBlank(asText(canonical.get("queryClass")), "LIST_REQUESTS");
        String entityKey = defaultIfBlank(asText(canonical.get("entity")), "REQUEST");
        Map<String, Object> mappedFilter = loadMappedFilterForOption(entityKey, queryClass, optionKey);
        if (mappedFilter.isEmpty()) {
            return null;
        }
        SemanticFilter selectedFilter = new SemanticFilter(
                normalizeField(asText(mappedFilter.get("field"))),
                normalizeOp(asText(mappedFilter.get("op"))),
                mappedFilter.get("value")
        );
        List<SemanticFilter> filters = new ArrayList<>(parseFiltersFromObject(canonical.get("filters")));
        filters.removeIf(f -> f != null && f.field() != null && f.field().equalsIgnoreCase(selectedFilter.field()));
        filters.add(selectedFilter);

        CanonicalIntent intent = new CanonicalIntent(
                normalizeIntent(asText(canonical.get("intent"))),
                entityKey.trim().toUpperCase(Locale.ROOT),
                normalizeQueryClass(queryClass),
                filters,
                parseTimeRangeFromObject(canonical.get("timeRange"), request),
                parseSortFromObject(canonical.get("sort")),
                normalizeLimit(toIntObject(canonical.get("limit")))
        );

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("parser", "clarification_selection");
        trace.put("normalizations", List.of("selected option " + selectedIndex + " -> " + optionKey));
        trace.put("question", question);
        trace.put("sanitized", true);
        trace.put("clarificationThreshold", clarificationThreshold());
        return new ParsedInterpret(intent, 0.98d, false, null, List.of(), trace, null, true, optionKey, String.valueOf(selectedIndex));
    }

    private ParsedInterpret applyClarificationContract(ParsedInterpret parsed) {
        if (parsed == null) {
            return null;
        }
        boolean resolved = parsed.clarificationResolved();
        String selectedOptionKey = parsed.selectedOptionKey();
        boolean hasSelection = selectedOptionKey != null && !selectedOptionKey.isBlank();
        if (!resolved && !hasSelection) {
            return parsed;
        }

        CanonicalIntent canonical = parsed.canonicalIntent();
        if (canonical == null) {
            return parsed;
        }
        String entityKey = defaultIfBlank(canonical.entity(), "REQUEST");
        String queryClassKey = defaultIfBlank(canonical.queryClass(), "LIST_REQUESTS");
        List<SemanticFilter> filters = new ArrayList<>(canonical.filters() == null ? List.of() : canonical.filters());

        boolean selectionApplied = false;
        if (hasSelection) {
            Map<String, Object> mappedFilter = loadMappedFilterForOption(entityKey, queryClassKey, selectedOptionKey);
            if (!mappedFilter.isEmpty()) {
                String field = normalizeField(asText(mappedFilter.get("field")));
                if (field != null && !field.isBlank()) {
                    SemanticFilter selectedFilter = new SemanticFilter(
                            field,
                            normalizeOp(asText(mappedFilter.get("op"))),
                            mappedFilter.get("value")
                    );
                    filters.removeIf(f -> f != null && f.field() != null && f.field().equalsIgnoreCase(field));
                    filters.add(selectedFilter);
                    selectionApplied = true;
                }
            }
        }

        boolean effectiveResolved = resolved || selectionApplied;
        if (!effectiveResolved) {
            return parsed;
        }

        CanonicalIntent updated = new CanonicalIntent(
                normalizeIntent(canonical.intent()),
                entityKey.trim().toUpperCase(Locale.ROOT),
                normalizeQueryClass(queryClassKey),
                filters,
                canonical.timeRange(),
                canonical.sort() == null ? List.of() : canonical.sort(),
                normalizeLimit(canonical.limit())
        );

        Map<String, Object> trace = new LinkedHashMap<>(parsed.trace());
        trace.put("clarificationResolved", true);
        if (hasSelection) {
            trace.put("selectedOptionKey", selectedOptionKey);
        }
        if (parsed.clarificationAnswerText() != null && !parsed.clarificationAnswerText().isBlank()) {
            trace.put("clarificationAnswerText", parsed.clarificationAnswerText());
        }

        return new ParsedInterpret(
                updated,
                Math.max(parsed.confidence(), 0.95d),
                false,
                null,
                List.of(),
                trace,
                parsed.placeholderValue(),
                true,
                selectedOptionKey,
                parsed.clarificationAnswerText()
        );
    }

    private Map<String, Object> loadMappedFilterForOption(String entityKey, String queryClassKey, String optionKey) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || optionKey == null || optionKey.isBlank()) {
            return Map.of();
        }
        String sql = """
                SELECT mapped_filter_json
                FROM ce_semantic_ambiguity_option
                WHERE enabled = true
                  AND UPPER(option_key) = UPPER(:optionKey)
                  AND (entity_key IS NULL OR UPPER(entity_key) = UPPER(:entityKey))
                  AND (query_class_key IS NULL OR UPPER(query_class_key) = UPPER(:queryClassKey))
                ORDER BY COALESCE(priority, 999999)
                LIMIT 1
                """;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of(
                    "optionKey", optionKey,
                    "entityKey", entityKey == null ? "" : entityKey,
                    "queryClassKey", queryClassKey == null ? "" : queryClassKey
            ));
            if (rows.isEmpty()) {
                return Map.of();
            }
            return parseJsonObject(rows.getFirst().get("mapped_filter_json"));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Integer parseSelectionNumber(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.trim();
        if (!q.matches("^\\d{1,2}$")) {
            return null;
        }
        try {
            return Integer.parseInt(q);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> lastObservationByTool(List<Map<String, Object>> observations, String toolCode) {
        if (observations == null || observations.isEmpty()) {
            return Map.of();
        }
        for (int i = observations.size() - 1; i >= 0; i--) {
            Map<String, Object> item = observations.get(i);
            if (item == null) {
                continue;
            }
            String code = asText(item.get("toolCode"));
            if (code != null && code.equalsIgnoreCase(toolCode)) {
                return item;
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> safeMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                out.add(safeMap(item));
            }
            return out;
        }
        return List.of();
    }

    private List<SemanticFilter> parseFiltersFromObject(Object value) {
        if (value instanceof List<?> list) {
            List<SemanticFilter> out = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> filter = safeMap(item);
                String field = normalizeField(asText(filter.get("field")));
                if (field == null || field.isBlank()) {
                    continue;
                }
                out.add(new SemanticFilter(field, normalizeOp(asText(filter.get("op"))), filter.get("value")));
            }
            return out;
        }
        return List.of();
    }

    private List<SemanticSort> parseSortFromObject(Object value) {
        if (value instanceof List<?> list) {
            List<SemanticSort> out = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> sort = safeMap(item);
                String field = normalizeField(asText(sort.get("field")));
                if (field == null || field.isBlank()) {
                    continue;
                }
                out.add(new SemanticSort(field, normalizeDirection(asText(sort.get("direction")))));
            }
            return out;
        }
        return List.of();
    }

    private SemanticTimeRange parseTimeRangeFromObject(Object value, SemanticInterpretRequest request) {
        Map<String, Object> map = safeMap(value);
        if (map.isEmpty()) {
            return null;
        }
        return parseTimeRange(mapToJsonNode(map), request);
    }

    private JsonNode mapToJsonNode(Map<String, Object> map) {
        return mapper.valueToTree(map == null ? Map.of() : map);
    }

    private Integer toIntObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private SemanticFilter extractExplicitBusinessFieldFilter(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Pattern explicitPattern = Pattern.compile("(?i)\\b([a-z][a-z0-9_ ]{2,60})\\b\\s*(?:=|is|equals?)\\s*['\"]?([A-Za-z0-9_-]{1,80})['\"]?");
        Matcher matcher = explicitPattern.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        String fieldPhrase = matcher.group(1);
        String value = matcher.group(2);
        String resolvedField = resolveBusinessFieldFromPhrase(fieldPhrase);
        if (resolvedField == null || resolvedField.isBlank() || value == null || value.isBlank()) {
            return null;
        }
        return new SemanticFilter(resolvedField, "EQ", value);
    }

    private String resolveBusinessFieldFromPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return null;
        }
        String normalized = phrase.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        SemanticModel model = semanticModelRegistry == null ? null : semanticModelRegistry.getModel();
        if (model == null || model.entities() == null) {
            return null;
        }
        for (SemanticEntity entity : model.entities().values()) {
            if (entity == null || entity.fields() == null) {
                continue;
            }
            for (String field : entity.fields().keySet()) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                String fieldNorm = field.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (normalized.equals(fieldNorm)) {
                    return normalizeField(field);
                }
            }
        }
        return null;
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

        return new CanonicalIntent(intent, normalizeEntityKey(entity), queryClass, filters, timeRange, sort, limit);
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

    private List<SemanticAmbiguity> applyPlaceholderToAmbiguities(List<SemanticAmbiguity> ambiguities, String replacement) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return List.of();
        }
        List<SemanticAmbiguity> out = new ArrayList<>();
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity == null) {
                continue;
            }
            List<SemanticAmbiguityOption> options = ambiguity.options() == null ? List.of() : ambiguity.options();
            List<SemanticAmbiguityOption> optionOut = new ArrayList<>();
            for (SemanticAmbiguityOption option : options) {
                if (option == null) {
                    continue;
                }
                optionOut.add(new SemanticAmbiguityOption(
                        replacePlaceholderToken(option.key(), replacement),
                        replacePlaceholderToken(option.label(), replacement),
                        option.confidence()
                ));
            }
            out.add(new SemanticAmbiguity(
                    ambiguity.type(),
                    replacePlaceholderToken(ambiguity.code(), replacement),
                    replacePlaceholderToken(ambiguity.message(), replacement),
                    ambiguity.required(),
                    optionOut
            ));
        }
        return List.copyOf(out);
    }

    private String replacePlaceholderToken(String value, String replacement) {
        if (value == null || value.isBlank() || replacement == null || replacement.isBlank()) {
            return value;
        }
        return value
                .replace("{{value}}", replacement)
                .replace("{{ value }}", replacement)
                .replace("{{  value  }}", replacement);
    }

    private String appendSemanticGuardrailBlock(String renderedUser,
                                                List<String> allowedEntityKeys,
                                                Map<String, Object> allowedFieldsByEntity,
                                                Map<String, Object> semanticMetadata,
                                                List<Map<String, Object>> semanticAmbiguityOptions,
                                                List<Map<String, Object>> semanticConcepts,
                                                List<Map<String, Object>> semanticJoinHints,
                                                List<Map<String, Object>> semanticMappings,
                                                List<Map<String, Object>> semanticQueryClasses,
                                                List<Map<String, Object>> semanticSynonyms,
                                                List<Map<String, Object>> semanticEmbeddingCatalogForPrompt) {
        String base = renderedUser == null ? "" : renderedUser;
        if (base.contains("Strict guardrails (DB-driven):")) {
            return base;
        }
        boolean hasAllMarkers = base.contains("allowed_entity_keys")
                && base.contains("allowed_fields_by_entity")
                && base.contains("semantic_metadata")
                && base.contains("ce_semantic_ambiguity_option")
                && base.contains("ce_semantic_concept")
                && base.contains("ce_semantic_join_hint")
                && base.contains("ce_semantic_mapping")
                && base.contains("ce_semantic_query_class")
                && base.contains("ce_semantic_synonym")
                && base.contains("ce_semantic_concept_embedding");
        boolean hasTemplateSections = base.contains("Allowed entity keys (strict):")
                && base.contains("Allowed fields by entity (strict):")
                && base.contains("Semantic metadata (DB-driven):")
                && base.contains("ce_semantic_ambiguity_option:")
                && base.contains("ce_semantic_concept:")
                && base.contains("ce_semantic_join_hint:")
                && base.contains("ce_semantic_mapping:")
                && base.contains("ce_semantic_query_class:")
                && base.contains("ce_semantic_synonym:")
                && base.contains("ce_semantic_concept_embedding:");
        if (hasAllMarkers || hasTemplateSections) {
            return base;
        }
        return base + "\n\nStrict guardrails (DB-driven):\n"
                + "allowed_entity_keys:\n" + safeJson(allowedEntityKeys) + "\n\n"
                + "allowed_fields_by_entity:\n" + safeJson(allowedFieldsByEntity) + "\n\n"
                + "semantic_metadata:\n" + safeJson(semanticMetadata) + "\n\n"
                + "ce_semantic_ambiguity_option:\n" + safeJson(semanticAmbiguityOptions) + "\n\n"
                + "ce_semantic_concept:\n" + safeJson(semanticConcepts) + "\n\n"
                + "ce_semantic_join_hint:\n" + safeJson(semanticJoinHints) + "\n\n"
                + "ce_semantic_mapping:\n" + safeJson(semanticMappings) + "\n\n"
                + "ce_semantic_query_class:\n" + safeJson(semanticQueryClasses) + "\n\n"
                + "ce_semantic_synonym:\n" + safeJson(semanticSynonyms) + "\n\n"
                + "ce_semantic_concept_embedding:\n" + safeJson(semanticEmbeddingCatalogForPrompt);
    }

    private List<Map<String, Object>> slimEmbeddingCatalogForPrompt(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> slim = new LinkedHashMap<>();
            slim.put("concept_key", asText(row.get("concept_key")));
            String sourceText = asText(row.get("source_text"));
            if (sourceText != null && sourceText.length() > 220) {
                sourceText = sourceText.substring(0, 220) + "...";
            }
            slim.put("source_text", sourceText);
            slim.put("confidence_score", row.get("confidence_score"));
            slim.put("priority", row.get("priority"));
            out.add(slim);
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    private PromptPackage buildPromptPackage(String question,
                                             SemanticInterpretRequest request,
                                             UUID conversationId,
                                             EngineSession session) {
        PromptPackage dbPrompt = renderPromptFromTemplate(question, request, conversationId, session);
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
        List<String> allAllowedEntityKeys = loadAllowedEntityKeys();
        Map<String, Object> allAllowedFieldsByEntity = loadAllowedFieldsByEntity();
        List<Map<String, Object>> semanticAmbiguityOptions = loadSemanticAmbiguityOptionRows();
        List<Map<String, Object>> semanticConcepts = loadSemanticConceptRows();
        List<Map<String, Object>> semanticJoinHints = loadSemanticJoinHintRows();
        List<Map<String, Object>> semanticMappings = loadSemanticMappingRows();
        List<Map<String, Object>> semanticQueryClasses = loadSemanticQueryClassRows();
        List<Map<String, Object>> semanticSynonyms = loadSemanticSynonymRows();
        List<Map<String, Object>> semanticEmbeddingCatalog = loadSemanticEmbeddingCatalogRows(queryClassKey);
        PromptSemanticScope scope = scopePromptSemanticPayload(
                safeQuestion,
                queryClassKey,
                entityKeyHint,
                allAllowedEntityKeys,
                allAllowedFieldsByEntity,
                semanticAmbiguityOptions,
                semanticConcepts,
                semanticJoinHints,
                semanticMappings,
                semanticQueryClasses,
                semanticSynonyms,
                semanticEmbeddingCatalog
        );
        List<String> allowedEntityKeys = scope.allowedEntityKeys();
        Map<String, Object> allowedFieldsByEntity = scope.allowedFieldsByEntity();
        semanticAmbiguityOptions = scope.semanticAmbiguityOptions();
        semanticConcepts = scope.semanticConcepts();
        semanticJoinHints = scope.semanticJoinHints();
        semanticMappings = scope.semanticMappings();
        semanticQueryClasses = scope.semanticQueryClasses();
        semanticSynonyms = scope.semanticSynonyms();
        semanticEmbeddingCatalog = scope.semanticEmbeddingCatalog();
        List<Map<String, Object>> semanticEmbeddingCatalogForPrompt = slimEmbeddingCatalogForPrompt(semanticEmbeddingCatalog);
        Map<String, Object> semanticMetadata = loadSemanticMetadataForPrompt(allowedEntityKeys, allowedFieldsByEntity, semanticQueryClasses);
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

                Allowed entity keys (strict):
                %s

                Allowed fields by entity (strict):
                %s

                Semantic metadata (DB-driven):
                %s

                ce_semantic_ambiguity_option:
                %s

                ce_semantic_concept:
                %s

                ce_semantic_join_hint:
                %s

                ce_semantic_mapping:
                %s

                ce_semantic_query_class:
                %s

                ce_semantic_synonym:
                %s

                ce_semantic_concept_embedding:
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
                safeJson(allowedEntityKeys),
                safeJson(allowedFieldsByEntity),
                safeJson(semanticMetadata),
                safeJson(semanticAmbiguityOptions),
                safeJson(semanticConcepts),
                safeJson(semanticJoinHints),
                safeJson(semanticMappings),
                safeJson(semanticQueryClasses),
                safeJson(semanticSynonyms),
                safeJson(semanticEmbeddingCatalogForPrompt),
                safeJson(ambiguityOptions),
                timezone
        );

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("question", safeQuestion);
        vars.put("current_date", nowDate);
        vars.put("current_timezone", timezone);
        vars.put("hints", hints);
        vars.put("semantic_context", context);
        vars.put("query_class_key", queryClassKey);
        vars.put("query_class_config", queryClassConfig);
        vars.put("semantic_fields", semanticFields);
        vars.put("semantic_allowed_values", semanticAllowedValues);
        vars.put("allowed_entity_keys", allowedEntityKeys);
        vars.put("allowed_fields_by_entity", allowedFieldsByEntity);
        vars.put("semantic_metadata", semanticMetadata);
        vars.put("ce_semantic_ambiguity_option", semanticAmbiguityOptions);
        vars.put("ce_semantic_concept", semanticConcepts);
        vars.put("ce_semantic_join_hint", semanticJoinHints);
        vars.put("ce_semantic_mapping", semanticMappings);
        vars.put("ce_semantic_query_class", semanticQueryClasses);
        vars.put("ce_semantic_synonym", semanticSynonyms);
        vars.put("ce_semantic_concept_embedding", semanticEmbeddingCatalogForPrompt);
        vars.put("ambiguity_options", ambiguityOptions);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("promptSource", "fallback");
        meta.put("responseType", "SEMANTIC_INTERPRET");
        meta.put("intent", "SEMANTIC_QUERY");
        meta.put("state", "ANALYZE");
        meta.put("vars", vars);
        return new PromptPackage(systemPrompt, userPrompt, meta);
    }

    private PromptPackage renderPromptFromTemplate(String question,
                                                   SemanticInterpretRequest request,
                                                   UUID conversationId,
                                                   EngineSession session) {
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
        List<String> allAllowedEntityKeys = loadAllowedEntityKeys();
        Map<String, Object> allAllowedFieldsByEntity = loadAllowedFieldsByEntity();
        List<Map<String, Object>> semanticAmbiguityOptions = loadSemanticAmbiguityOptionRows();
        List<Map<String, Object>> semanticConcepts = loadSemanticConceptRows();
        List<Map<String, Object>> semanticJoinHints = loadSemanticJoinHintRows();
        List<Map<String, Object>> semanticMappings = loadSemanticMappingRows();
        List<Map<String, Object>> semanticQueryClasses = loadSemanticQueryClassRows();
        List<Map<String, Object>> semanticSynonyms = loadSemanticSynonymRows();
        List<Map<String, Object>> semanticEmbeddingCatalog = loadSemanticEmbeddingCatalogRows(queryClassKey);
        PromptSemanticScope scope = scopePromptSemanticPayload(
                defaultIfBlank(question, ""),
                queryClassKey,
                entityKeyHint,
                allAllowedEntityKeys,
                allAllowedFieldsByEntity,
                semanticAmbiguityOptions,
                semanticConcepts,
                semanticJoinHints,
                semanticMappings,
                semanticQueryClasses,
                semanticSynonyms,
                semanticEmbeddingCatalog
        );
        List<String> allowedEntityKeys = scope.allowedEntityKeys();
        Map<String, Object> allowedFieldsByEntity = scope.allowedFieldsByEntity();
        semanticAmbiguityOptions = scope.semanticAmbiguityOptions();
        semanticConcepts = scope.semanticConcepts();
        semanticJoinHints = scope.semanticJoinHints();
        semanticMappings = scope.semanticMappings();
        semanticQueryClasses = scope.semanticQueryClasses();
        semanticSynonyms = scope.semanticSynonyms();
        semanticEmbeddingCatalog = scope.semanticEmbeddingCatalog();
        List<Map<String, Object>> semanticEmbeddingCatalogForPrompt = slimEmbeddingCatalogForPrompt(semanticEmbeddingCatalog);
        Map<String, Object> semanticMetadata = loadSemanticMetadataForPrompt(allowedEntityKeys, allowedFieldsByEntity, semanticQueryClasses);
        List<Map<String, Object>> ambiguityOptions = loadAmbiguityOptions(queryClassKey, entityKeyHint, defaultIfBlank(question, ""), request);

        Map<String, Object> promptVars = new LinkedHashMap<>();
        promptVars.put("hints", safeJson(hints));
        promptVars.put("semantic_context", safeJson(semanticContext));
        promptVars.put("query_class_key", queryClassKey);
        promptVars.put("query_class_config", safeJson(queryClassConfig));
        promptVars.put("semantic_fields", safeJson(semanticFields));
        promptVars.put("semantic_allowed_values", safeJson(semanticAllowedValues));
        promptVars.put("allowed_entity_keys", safeJson(allowedEntityKeys));
        promptVars.put("allowed_fields_by_entity", safeJson(allowedFieldsByEntity));
        promptVars.put("semantic_metadata", safeJson(semanticMetadata));
        promptVars.put("ce_semantic_ambiguity_option", safeJson(semanticAmbiguityOptions));
        promptVars.put("ce_semantic_concept", safeJson(semanticConcepts));
        promptVars.put("ce_semantic_join_hint", safeJson(semanticJoinHints));
        promptVars.put("ce_semantic_mapping", safeJson(semanticMappings));
        promptVars.put("ce_semantic_query_class", safeJson(semanticQueryClasses));
        promptVars.put("ce_semantic_synonym", safeJson(semanticSynonyms));
        promptVars.put("ce_semantic_concept_embedding", safeJson(semanticEmbeddingCatalogForPrompt));
        promptVars.put("ambiguity_options", safeJson(ambiguityOptions));

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
                .extra(promptVars)
                .build();

        Map<String, Object> promptVarsAudit = new LinkedHashMap<>();
        promptVarsAudit.put("tool", TOOL_CODE);
        promptVarsAudit.put("templateId", template.getTemplateId());
        promptVarsAudit.put("intent", intent);
        promptVarsAudit.put("state", state);
        promptVarsAudit.put("promptVars", promptVars);
        audit("SEMANTIC_INTERPRET_PROMPT_VARS", conversationId, promptVarsAudit);
        verbose(session, "SEMANTIC_INTERPRET_PROMPT_VARS", false, promptVarsAudit);

        String renderedSystem;
        String renderedUser;
        try {
            renderedSystem = promptTemplateRenderer.render(template.getSystemPrompt(), ctx);
            renderedUser = promptTemplateRenderer.render(template.getUserPrompt(), ctx);
        } catch (Exception ex) {
            Map<String, Object> promptError = new LinkedHashMap<>();
            promptError.put("tool", TOOL_CODE);
            promptError.put("templateId", template.getTemplateId());
            promptError.put("intent", intent);
            promptError.put("state", state);
            promptError.put("errorClass", ex.getClass().getName());
            promptError.put("errorMessage", ex.getMessage());
            promptError.put("system_prompt_template", template.getSystemPrompt());
            promptError.put("user_prompt_template", template.getUserPrompt());
            promptError.put("promptVars", promptVars);
            audit("SEMANTIC_INTERPRET_PROMPT_RENDER_ERROR", conversationId, promptError);
            verbose(session, "SEMANTIC_INTERPRET_PROMPT_RENDER_ERROR", true, promptError);
            return null;
        }
        renderedUser = appendSemanticGuardrailBlock(
                renderedUser,
                allowedEntityKeys,
                allowedFieldsByEntity,
                semanticMetadata,
                semanticAmbiguityOptions,
                semanticConcepts,
                semanticJoinHints,
                semanticMappings,
                semanticQueryClasses,
                semanticSynonyms,
                semanticEmbeddingCatalogForPrompt
        );

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("question", defaultIfBlank(question, ""));
        vars.put("current_date", nowDate);
        vars.put("current_timezone", timezone);
        vars.put("hints", hints);
        vars.put("semantic_context", semanticContext);
        vars.put("query_class_key", queryClassKey);
        vars.put("query_class_config", queryClassConfig);
        vars.put("semantic_fields", semanticFields);
        vars.put("semantic_allowed_values", semanticAllowedValues);
        vars.put("allowed_entity_keys", allowedEntityKeys);
        vars.put("allowed_fields_by_entity", allowedFieldsByEntity);
        vars.put("semantic_metadata", semanticMetadata);
        vars.put("ce_semantic_ambiguity_option", semanticAmbiguityOptions);
        vars.put("ce_semantic_concept", semanticConcepts);
        vars.put("ce_semantic_join_hint", semanticJoinHints);
        vars.put("ce_semantic_mapping", semanticMappings);
        vars.put("ce_semantic_query_class", semanticQueryClasses);
        vars.put("ce_semantic_synonym", semanticSynonyms);
        vars.put("ce_semantic_concept_embedding", semanticEmbeddingCatalogForPrompt);
        vars.put("ambiguity_options", ambiguityOptions);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("promptSource", "ce_prompt_template");
        meta.put("templateId", template.getTemplateId());
        meta.put("responseType", "SEMANTIC_INTERPRET");
        meta.put("intent", intent);
        meta.put("state", state);
        meta.put("vars", vars);
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

    private Set<String> extractQuestionTokens(String question) {
        if (question == null || question.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : question.split("[^A-Za-z0-9_]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = normalizeField(token);
            if (normalized != null && !normalized.isBlank()) {
                out.add(normalized.toLowerCase(Locale.ROOT));
            }
        }
        return out;
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

    private List<String> loadAllowedEntityKeys() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        Set<String> out = new LinkedHashSet<>();
        if (jdbc != null) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT DISTINCT entity_key
                        FROM ce_semantic_mapping
                        WHERE enabled = true
                          AND entity_key IS NOT NULL
                          AND btrim(entity_key) <> ''
                        ORDER BY entity_key
                        """, Map.of());
                for (Map<String, Object> row : rows) {
                    String key = normalizeEntityKey(asText(row.get("entity_key")));
                    if (key != null && !key.isBlank()) {
                        out.add(key);
                    }
                }
            } catch (Exception ignored) {
                // fallback below
            }
            try {
                List<Map<String, Object>> conceptRows = jdbc.queryForList("""
                        SELECT DISTINCT concept_key
                        FROM ce_semantic_concept
                        WHERE enabled = true
                          AND concept_kind = 'ENTITY'
                          AND concept_key IS NOT NULL
                          AND btrim(concept_key) <> ''
                        ORDER BY concept_key
                        """, Map.of());
                for (Map<String, Object> row : conceptRows) {
                    String key = normalizeEntityKey(asText(row.get("concept_key")));
                    if (key != null && !key.isBlank()) {
                        out.add(key);
                    }
                }
            } catch (Exception ignored) {
                // fallback below
            }
        }
        if (out.isEmpty()) {
            SemanticModel model = semanticModelRegistry == null ? null : semanticModelRegistry.getModel();
            if (model != null && model.entities() != null) {
                model.entities().keySet().forEach(e -> {
                    String key = normalizeEntityKey(e);
                    if (key != null && !key.isBlank()) {
                        out.add(key);
                    }
                });
            }
        }
        return List.copyOf(out);
    }

    private Map<String, Object> loadAllowedFieldsByEntity() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        if (jdbc != null) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT entity_key, field_key
                        FROM ce_semantic_mapping
                        WHERE enabled = true
                          AND entity_key IS NOT NULL
                          AND field_key IS NOT NULL
                          AND btrim(entity_key) <> ''
                          AND btrim(field_key) <> ''
                        ORDER BY entity_key, COALESCE(priority, 999999)
                        """, Map.of());
                for (Map<String, Object> row : rows) {
                    String entityKey = normalizeEntityKey(asText(row.get("entity_key")));
                    String fieldKey = normalizeField(asText(row.get("field_key")));
                    if (entityKey == null || entityKey.isBlank() || fieldKey == null || fieldKey.isBlank()) {
                        continue;
                    }
                    grouped.computeIfAbsent(entityKey, k -> new LinkedHashSet<>()).add(fieldKey);
                }
            } catch (Exception ignored) {
                // fallback below
            }
        }
        if (grouped.isEmpty()) {
            SemanticModel model = semanticModelRegistry == null ? null : semanticModelRegistry.getModel();
            if (model != null && model.entities() != null) {
                for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
                    String entityKey = normalizeEntityKey(entry.getKey());
                    SemanticEntity entity = entry.getValue();
                    if (entity == null || entity.fields() == null) {
                        continue;
                    }
                    Set<String> fields = grouped.computeIfAbsent(entityKey, k -> new LinkedHashSet<>());
                    for (String field : entity.fields().keySet()) {
                        String fieldKey = normalizeField(field);
                        if (fieldKey != null && !fieldKey.isBlank()) {
                            fields.add(fieldKey);
                        }
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        grouped.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    private Map<String, Object> loadSemanticMetadataForPrompt(List<String> allowedEntityKeys,
                                                              Map<String, Object> allowedFieldsByEntity,
                                                              List<Map<String, Object>> semanticQueryClasses) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entities", allowedEntityKeys == null ? List.of() : allowedEntityKeys);
        out.put("fieldsByEntity", allowedFieldsByEntity == null ? Map.of() : allowedFieldsByEntity);
        out.put("queryClasses", semanticQueryClasses == null ? List.of() : semanticQueryClasses);
        out.put("scope", Map.of(
                "entityCount", allowedEntityKeys == null ? 0 : allowedEntityKeys.size(),
                "queryClassCount", semanticQueryClasses == null ? 0 : semanticQueryClasses.size()
        ));
        return out;
    }

    private PromptSemanticScope scopePromptSemanticPayload(String question,
                                                           String queryClassKey,
                                                           String entityKeyHint,
                                                           List<String> allAllowedEntityKeys,
                                                           Map<String, Object> allAllowedFieldsByEntity,
                                                           List<Map<String, Object>> ambiguityOptionRows,
                                                           List<Map<String, Object>> conceptRows,
                                                           List<Map<String, Object>> joinHintRows,
                                                           List<Map<String, Object>> mappingRows,
                                                           List<Map<String, Object>> queryClassRows,
                                                           List<Map<String, Object>> synonymRows,
                                                           List<Map<String, Object>> embeddingCatalogRows) {
        Set<String> relevantEntities = new LinkedHashSet<>();
        if (entityKeyHint != null && !entityKeyHint.isBlank()) {
            relevantEntities.add(normalizeEntityKey(entityKeyHint));
        }
        relevantEntities.addAll(selectEntitiesByEmbeddingSimilarity(question, queryClassKey, embeddingCatalogRows, mappingRows));
        Set<String> questionTokens = extractQuestionTokens(question);
        String questionLower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (Map<String, Object> row : embeddingCatalogRows == null ? List.<Map<String, Object>>of() : embeddingCatalogRows) {
            String conceptKey = asText(row.get("concept_key"));
            if (conceptKey == null || conceptKey.isBlank()) {
                continue;
            }
            String sourceText = asText(firstNonNull(row.get("source_text"), row.get("concept_key")));
            if (sourceText == null || sourceText.isBlank()) {
                continue;
            }
            if (sourceTextMatchesQuestion(sourceText, questionLower, questionTokens)) {
                relevantEntities.addAll(entityKeysForConcept(conceptKey, queryClassKey, mappingRows));
            }
        }
        for (Map<String, Object> row : mappingRows == null ? List.<Map<String, Object>>of() : mappingRows) {
            String entityKey = normalizeEntityKey(asText(row.get("entity_key")));
            if (entityKey == null || entityKey.isBlank()) {
                continue;
            }
            String rowQueryClass = asText(row.get("query_class_key"));
            if (!queryClassMatches(queryClassKey, rowQueryClass)) {
                continue;
            }
            String fieldKey = normalizeField(asText(row.get("field_key")));
            if (fieldKey != null && questionTokens.contains(fieldKey.toLowerCase(Locale.ROOT))) {
                relevantEntities.add(entityKey);
            }
        }
        if (relevantEntities.isEmpty() && allAllowedEntityKeys != null && !allAllowedEntityKeys.isEmpty()) {
            String first = normalizeEntityKey(allAllowedEntityKeys.getFirst());
            if (first != null && !first.isBlank()) {
                relevantEntities.add(first);
            }
        }
        List<String> scopedEntities = new ArrayList<>();
        if (allAllowedEntityKeys != null) {
            for (String entity : allAllowedEntityKeys) {
                String normalized = normalizeEntityKey(entity);
                if (relevantEntities.contains(normalized)) {
                    scopedEntities.add(normalized);
                }
            }
        }
        if (scopedEntities.isEmpty()) {
            scopedEntities = allAllowedEntityKeys == null ? List.of() : List.copyOf(allAllowedEntityKeys);
        }
        Set<String> scopedEntitySet = new LinkedHashSet<>(scopedEntities);

        Map<String, Object> scopedFieldsByEntity = new LinkedHashMap<>();
        if (allAllowedFieldsByEntity != null) {
            for (Map.Entry<String, Object> entry : allAllowedFieldsByEntity.entrySet()) {
                String key = normalizeEntityKey(entry.getKey());
                if (scopedEntitySet.contains(key)) {
                    scopedFieldsByEntity.put(key, entry.getValue());
                }
            }
        }

        List<Map<String, Object>> scopedQueryClasses = new ArrayList<>();
        for (Map<String, Object> row : queryClassRows == null ? List.<Map<String, Object>>of() : queryClassRows) {
            String rowQueryClass = asText(row.get("query_class_key"));
            if (queryClassMatches(queryClassKey, rowQueryClass)) {
                scopedQueryClasses.add(row);
            }
        }

        List<Map<String, Object>> scopedMappings = new ArrayList<>();
        Set<String> conceptKeys = new LinkedHashSet<>();
        Set<String> mappedTables = new LinkedHashSet<>();
        for (Map<String, Object> row : mappingRows == null ? List.<Map<String, Object>>of() : mappingRows) {
            String entityKey = normalizeEntityKey(asText(row.get("entity_key")));
            if (!scopedEntitySet.contains(entityKey)) {
                continue;
            }
            String rowQueryClass = asText(row.get("query_class_key"));
            if (!queryClassMatches(queryClassKey, rowQueryClass)) {
                continue;
            }
            scopedMappings.add(row);
            String conceptKey = asText(row.get("concept_key"));
            if (conceptKey != null && !conceptKey.isBlank()) {
                conceptKeys.add(conceptKey.toUpperCase(Locale.ROOT));
            }
            String table = asText(row.get("mapped_table"));
            if (table != null && !table.isBlank()) {
                mappedTables.add(table.toLowerCase(Locale.ROOT));
            }
        }

        List<Map<String, Object>> scopedAmbiguityRows = new ArrayList<>();
        for (Map<String, Object> row : ambiguityOptionRows == null ? List.<Map<String, Object>>of() : ambiguityOptionRows) {
            String entityKey = normalizeEntityKey(asText(row.get("entity_key")));
            if (entityKey != null && !entityKey.isBlank() && !scopedEntitySet.contains(entityKey)) {
                continue;
            }
            String rowQueryClass = asText(row.get("query_class_key"));
            if (!queryClassMatches(queryClassKey, rowQueryClass)) {
                continue;
            }
            scopedAmbiguityRows.add(row);
        }

        List<Map<String, Object>> scopedEmbeddingCatalog = new ArrayList<>();
        for (Map<String, Object> row : embeddingCatalogRows == null ? List.<Map<String, Object>>of() : embeddingCatalogRows) {
            String conceptKey = asText(row.get("concept_key"));
            if (conceptKey == null || conceptKey.isBlank()) {
                continue;
            }
            if (!conceptAppliesToScopedEntities(conceptKey, queryClassKey, scopedEntitySet, mappingRows)) {
                continue;
            }
            scopedEmbeddingCatalog.add(row);
            if (scopedEmbeddingCatalog.size() >= 25) {
                break;
            }
        }

        List<Map<String, Object>> scopedJoinHints = new ArrayList<>();
        for (Map<String, Object> row : joinHintRows == null ? List.<Map<String, Object>>of() : joinHintRows) {
            String base = asText(row.get("base_table_name"));
            String join = asText(row.get("join_table_name"));
            boolean match = mappedTables.isEmpty();
            if (!match) {
                match = (base != null && mappedTables.contains(base.toLowerCase(Locale.ROOT)))
                        || (join != null && mappedTables.contains(join.toLowerCase(Locale.ROOT)));
            }
            if (match) {
                scopedJoinHints.add(row);
            }
        }

        List<Map<String, Object>> scopedConcepts = new ArrayList<>();
        for (Map<String, Object> row : conceptRows == null ? List.<Map<String, Object>>of() : conceptRows) {
            String conceptKey = asText(row.get("concept_key"));
            String kind = asText(row.get("concept_kind"));
            if ("ENTITY".equalsIgnoreCase(kind) && conceptKey != null && scopedEntitySet.contains(normalizeEntityKey(conceptKey))) {
                scopedConcepts.add(row);
                continue;
            }
            if (conceptKey != null && conceptKeys.contains(conceptKey.toUpperCase(Locale.ROOT))) {
                scopedConcepts.add(row);
            }
        }
        if (scopedConcepts.isEmpty()) {
            scopedConcepts = conceptRows == null ? List.of() : conceptRows;
        }

        Set<String> scopedConceptKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : scopedConcepts) {
            String conceptKey = asText(row.get("concept_key"));
            if (conceptKey != null && !conceptKey.isBlank()) {
                scopedConceptKeys.add(conceptKey.toUpperCase(Locale.ROOT));
            }
        }
        List<Map<String, Object>> scopedSynonyms = new ArrayList<>();
        for (Map<String, Object> row : synonymRows == null ? List.<Map<String, Object>>of() : synonymRows) {
            String conceptKey = asText(row.get("concept_key"));
            if (conceptKey != null && scopedConceptKeys.contains(conceptKey.toUpperCase(Locale.ROOT))) {
                scopedSynonyms.add(row);
            }
        }

        return new PromptSemanticScope(
                scopedEntities,
                scopedFieldsByEntity,
                scopedAmbiguityRows,
                scopedConcepts,
                scopedJoinHints,
                scopedMappings,
                scopedQueryClasses,
                scopedSynonyms,
                scopedEmbeddingCatalog
        );
    }

    private Set<String> selectEntitiesByEmbeddingSimilarity(String question,
                                                            String queryClassKey,
                                                            List<Map<String, Object>> embeddingCatalogRows,
                                                            List<Map<String, Object>> mappingRows) {
        if (question == null || question.isBlank() || embeddingCatalogRows == null || embeddingCatalogRows.isEmpty()) {
            return Set.of();
        }
        ConvEngineMcpConfig.Db.Semantic.Vector vectorCfg = semanticCfg().getVector();
        if (vectorCfg == null || !vectorCfg.isEnabled()) {
            return Set.of();
        }
        float[] qVec;
        try {
            qVec = llmClient.generateEmbedding(null, question);
        } catch (Exception ex) {
            return Set.of();
        }
        if (qVec == null || qVec.length == 0) {
            return Set.of();
        }
        record EntityScore(String entityKey, double score) {}
        List<EntityScore> scored = new ArrayList<>();
        for (Map<String, Object> row : embeddingCatalogRows) {
            String conceptKey = asText(row.get("concept_key"));
            Set<String> conceptEntities = entityKeysForConcept(conceptKey, queryClassKey, mappingRows);
            if (conceptEntities.isEmpty()) {
                continue;
            }
            float[] rowVec = parseEmbedding(row.get("embedding_text"));
            if (rowVec == null || rowVec.length == 0 || rowVec.length != qVec.length) {
                continue;
            }
            double score = cosineSimilarity(qVec, rowVec);
            if (score > 0.45d) {
                for (String entityKey : conceptEntities) {
                    scored.add(new EntityScore(entityKey, score));
                }
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        int maxEntities = Math.max(1, semanticCfg().getRetrieval().getMaxEntities());
        Set<String> out = new LinkedHashSet<>();
        for (EntityScore score : scored) {
            out.add(score.entityKey());
            if (out.size() >= maxEntities) {
                break;
            }
        }
        return out;
    }

    private Set<String> entityKeysForConcept(String conceptKey,
                                             String queryClassKey,
                                             List<Map<String, Object>> mappingRows) {
        if (conceptKey == null || conceptKey.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : mappingRows == null ? List.<Map<String, Object>>of() : mappingRows) {
            String rowConcept = asText(row.get("concept_key"));
            if (rowConcept == null || !rowConcept.equalsIgnoreCase(conceptKey)) {
                continue;
            }
            if (!queryClassMatches(queryClassKey, asText(row.get("query_class_key")))) {
                continue;
            }
            String entityKey = normalizeEntityKey(asText(row.get("entity_key")));
            if (entityKey != null && !entityKey.isBlank()) {
                out.add(entityKey);
            }
        }
        return out;
    }

    private boolean conceptAppliesToScopedEntities(String conceptKey,
                                                   String queryClassKey,
                                                   Set<String> scopedEntitySet,
                                                   List<Map<String, Object>> mappingRows) {
        if (scopedEntitySet == null || scopedEntitySet.isEmpty()) {
            return true;
        }
        Set<String> conceptEntities = entityKeysForConcept(conceptKey, queryClassKey, mappingRows);
        if (conceptEntities.isEmpty()) {
            return false;
        }
        for (String entityKey : conceptEntities) {
            if (scopedEntitySet.contains(entityKey)) {
                return true;
            }
        }
        return false;
    }

    private float[] parseEmbedding(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String raw;
            if (value instanceof Map<?, ?> map) {
                Object maybe = map.get("value");
                raw = maybe == null ? String.valueOf(value) : String.valueOf(maybe);
            } else {
                raw = String.valueOf(value);
            }
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode node = mapper.readTree(raw);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            float[] out = new float[node.size()];
            for (int i = 0; i < node.size(); i++) {
                out[i] = (float) node.get(i).asDouble(0.0d);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double magA = 0.0d;
        double magB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            magA += a[i] * a[i];
            magB += b[i] * b[i];
        }
        if (magA <= 0.0d || magB <= 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }

    private boolean queryClassMatches(String requestQueryClass, String rowQueryClass) {
        if (requestQueryClass == null || requestQueryClass.isBlank()) {
            return true;
        }
        if (rowQueryClass == null || rowQueryClass.isBlank()) {
            return true;
        }
        return requestQueryClass.equalsIgnoreCase(rowQueryClass);
    }

    private boolean sourceTextMatchesQuestion(String sourceText, String questionLower, Set<String> questionTokens) {
        if (sourceText == null || sourceText.isBlank()) {
            return false;
        }
        String normalized = sourceText.toLowerCase(Locale.ROOT);
        if (questionLower != null && !questionLower.isBlank() && questionLower.contains(normalized)) {
            return true;
        }
        for (String token : normalized.split("[^a-z0-9_]+")) {
            if (token == null || token.isBlank() || token.length() < 3) {
                continue;
            }
            if (questionTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> allowedFieldSetForEntity(String entityKey) {
        Map<String, Object> map = loadAllowedFieldsByEntity();
        if (map.isEmpty()) {
            return Set.of();
        }
        Object fieldsObj = map.get(normalizeEntityKey(entityKey));
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object field : fields) {
            String normalized = normalizeField(String.valueOf(field));
            if (normalized != null && !normalized.isBlank()) {
                out.add(normalized.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private List<Map<String, Object>> loadAmbiguityOptions(String queryClassKey,
                                                           String entityKeyHint,
                                                           String question,
                                                           SemanticInterpretRequest request) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || queryClassKey == null || queryClassKey.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT entity_key, query_class_key, field_key, option_key, option_label,
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
                option.put("ambiguityCode", null);
                option.put("fieldKey", asText(row.get("field_key")));
                option.put("key", asText(row.get("option_key")));
                option.put("label", asText(row.get("option_label")));
                option.put("recommended", boolObject(row.get("recommended")));
                option.put("priority", toInt(row.get("priority"), 999999));
                option.put("mappedFilter", parseJsonObject(row.get("mapped_filter_json")));
                out.add(option);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticAmbiguityOptionRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT entity_key,
                           query_class_key,
                           field_key,
                           option_key,
                           option_label,
                           mapped_filter_json,
                           recommended,
                           priority
                    FROM ce_semantic_ambiguity_option
                    WHERE enabled = true
                    ORDER BY COALESCE(priority, 999999), option_label
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticConceptRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT concept_key,
                           concept_kind,
                           description,
                           tags,
                           priority
                    FROM ce_semantic_concept
                    WHERE enabled = true
                    ORDER BY COALESCE(priority, 999999), concept_key
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticJoinHintRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT base_table_name,
                           join_table_name,
                           join_priority
                    FROM ce_semantic_join_hint
                    WHERE enabled = true
                    ORDER BY COALESCE(join_priority, 999999), base_table_name, join_table_name
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticMappingRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT concept_key,
                           entity_key,
                           field_key,
                           mapped_table,
                           mapped_column,
                           operator_type,
                           query_class_key,
                           value_map_json,
                           priority
                    FROM ce_semantic_mapping
                    WHERE enabled = true
                    ORDER BY COALESCE(priority, 999999), entity_key, field_key
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticQueryClassRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT query_class_key,
                           description,
                           base_table_name,
                           allowed_filter_fields_json,
                           default_select_fields_json,
                           default_sort_fields_json,
                           priority
                    FROM ce_semantic_query_class
                    WHERE enabled = true
                    ORDER BY COALESCE(priority, 999999), query_class_key
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticSynonymRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT concept_key,
                           synonym_text AS synonym,
                           confidence_score AS confidence,
                           priority
                    FROM ce_semantic_synonym
                    WHERE enabled = true
                    ORDER BY COALESCE(priority, 999999), synonym_text
                    """, Map.of());
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSemanticEmbeddingCatalogRows(String queryClassKey) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        String sql = """
                SELECT e.concept_key,
                       e.source_text,
                       e.embedding_text,
                       e.confidence_score,
                       e.priority
                FROM ce_semantic_concept_embedding e
                WHERE e.enabled = true
                """;
        Map<String, Object> params = new LinkedHashMap<>();
        if (queryClassKey != null && !queryClassKey.isBlank()) {
            sql += """
                    AND EXISTS (
                        SELECT 1
                        FROM ce_semantic_mapping m
                        WHERE m.enabled = true
                          AND UPPER(m.concept_key) = UPPER(e.concept_key)
                          AND (m.query_class_key IS NULL OR btrim(m.query_class_key) = '' OR UPPER(m.query_class_key) = UPPER(:queryClassKey))
                    )
                    """;
            params.put("queryClassKey", queryClassKey);
        }
        sql += """
                ORDER BY COALESCE(e.priority, 999999), COALESCE(e.confidence_score, 0) DESC
                LIMIT 200
                """;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
            return normalizeDbRows(rows);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> normalizeDbRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            if (row != null) {
                row.forEach((k, v) -> normalized.put(String.valueOf(k), normalizeDbValue(v)));
            }
            out.add(normalized);
        }
        return out;
    }

    private Object normalizeDbValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((k, v) -> map.put(String.valueOf(k), normalizeDbValue(v)));
            return map;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(normalizeDbValue(item));
            }
            return out;
        }
        String className = value.getClass().getName();
        if ("org.postgresql.util.PGobject".equals(className)) {
            String raw = String.valueOf(value);
            if (raw.startsWith("{") && raw.endsWith("}")) {
                return parseJsonObject(raw);
            }
            if (raw.startsWith("[") && raw.endsWith("]")) {
                return parseJsonArray(raw);
            }
            return raw;
        }
        return value;
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

    private String extractTokenFallback(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher forMatcher = GENERIC_FOR_PATTERN.matcher(question);
        if (forMatcher.find()) {
            return forMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher tokenMatcher = GENERIC_TOKEN_PATTERN.matcher(question);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1);
            if (token == null || token.length() < 3) {
                continue;
            }
            String upper = token.toUpperCase(Locale.ROOT);
            if ("SHOW".equals(upper) || "LIST".equals(upper) || "FIND".equals(upper)
                    || "GET".equals(upper) || "TODAY".equals(upper) || "YESTERDAY".equals(upper)
                    || "WHERE".equals(upper) || "FOR".equals(upper) || "AND".equals(upper)
                    || "OR".equals(upper) || "WITH".equals(upper)) {
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

    private String normalizeEntityKey(String entity) {
        if (entity == null || entity.isBlank()) {
            return "REQUEST";
        }
        String withUnderscore = entity.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (withUnderscore.isBlank()) {
            return "REQUEST";
        }
        return withUnderscore.toUpperCase(Locale.ROOT);
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

    private boolean hasUnsupportedAmbiguity(List<SemanticAmbiguity> ambiguities) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return false;
        }
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity == null) {
                continue;
            }
            String code = ambiguity.code();
            if (code != null && code.toUpperCase(Locale.ROOT).contains("UNSUPPORTED")) {
                return true;
            }
            String message = ambiguity.message();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("not directly supported")) {
                return true;
            }
        }
        return false;
    }

    private String unsupportedAmbiguityMessage(List<SemanticAmbiguity> ambiguities) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return null;
        }
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity == null) {
                continue;
            }
            String code = ambiguity.code();
            String message = ambiguity.message();
            if (code != null && code.toUpperCase(Locale.ROOT).contains("UNSUPPORTED")
                    && message != null && !message.isBlank()) {
                return message;
            }
        }
        for (SemanticAmbiguity ambiguity : ambiguities) {
            if (ambiguity != null && ambiguity.message() != null && !ambiguity.message().isBlank()) {
                return ambiguity.message();
            }
        }
        return null;
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

    private SchemaPackage resolveOutputJsonSchema() {
        if (staticCacheService == null) {
            return new SchemaPackage(OUTPUT_JSON_SCHEMA, "fallback", null);
        }
        Optional<CeOutputSchema> exact = staticCacheService.findFirstOutputSchema("SEMANTIC_QUERY", "ANALYZE", "SEMANTIC_INTERPRET");
        if (exact.isPresent() && exact.get().getJsonSchema() != null && !exact.get().getJsonSchema().isBlank()) {
            CeOutputSchema schema = exact.get();
            return new SchemaPackage(schema.getJsonSchema(), "ce_output_schema:ANALYZE", schema.getSchemaId());
        }
        Optional<CeOutputSchema> any = staticCacheService.findFirstOutputSchema("SEMANTIC_QUERY", ConvEngineValue.ANY, "SEMANTIC_INTERPRET");
        if (any.isPresent() && any.get().getJsonSchema() != null && !any.get().getJsonSchema().isBlank()) {
            CeOutputSchema schema = any.get();
            return new SchemaPackage(schema.getJsonSchema(), "ce_output_schema:ANY", schema.getSchemaId());
        }
        return new SchemaPackage(OUTPUT_JSON_SCHEMA, "fallback", null);
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

    private Map<String, Object> safeMap(Object map) {
        if (!(map instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
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
            Map<String, Object> trace,
            String placeholderValue,
            boolean clarificationResolved,
            String selectedOptionKey,
            String clarificationAnswerText
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

    private record PromptSemanticScope(
            List<String> allowedEntityKeys,
            Map<String, Object> allowedFieldsByEntity,
            List<Map<String, Object>> semanticAmbiguityOptions,
            List<Map<String, Object>> semanticConcepts,
            List<Map<String, Object>> semanticJoinHints,
            List<Map<String, Object>> semanticMappings,
            List<Map<String, Object>> semanticQueryClasses,
            List<Map<String, Object>> semanticSynonyms,
            List<Map<String, Object>> semanticEmbeddingCatalog
    ) { }

    private record SchemaPackage(
            String schema,
            String source,
            Long schemaId
    ) { }
}
