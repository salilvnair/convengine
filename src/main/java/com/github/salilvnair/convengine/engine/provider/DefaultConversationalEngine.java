package com.github.salilvnair.convengine.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.ccf.core.model.ContainerComponentRequest;
import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;
import com.github.salilvnair.ccf.core.model.PageInfoRequest;
import com.github.salilvnair.ccf.core.model.type.RequestType;
import com.github.salilvnair.ccf.service.CcfCoreService;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.core.ConversationalEngineContainerInterceptor;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.model.ValidationResult;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.*;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.OutputPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.*;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class DefaultConversationalEngine implements ConversationalEngine {

    // Purpose keys for ce_prompt_template (standardize these values in DB)
    private static final String PURPOSE_SCHEMA_EXTRACTION   = "SCHEMA_EXTRACTION";
    private static final String PURPOSE_TEXT_RESPONSE       = "TEXT_RESPONSE";
    private static final String PURPOSE_JSON_RESPONSE       = "JSON_RESPONSE";
    private static final String PURPOSE_VALIDATION_DECISION = "VALIDATION_DECISION";

    private final ConversationRepository conversationRepo;
    private final RuleRepository ruleRepo;
    private final PolicyRepository policyRepo;
    private final ResponseRepository responseRepo;
    private final IntentClassifierRepository intentClassifierRepo;
    private final OutputSchemaRepository outputSchemaRepo;
    private final AuditRepository auditRepo;
    private final PromptTemplateRepository promptTemplateRepo;
    private final ContainerConfigRepository validationConfigRepo;
    private final CcfCoreService ccfCoreService;
    private final LlmClient llmClient;
    private final ConversationalEngineContainerInterceptor ceContainerInterceptor;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public EngineResult process(EngineContext engineContext) {

        UUID conversationId = UUID.fromString(engineContext.getConversationId());
        String userText = engineContext.getUserText();
        // ------------------------------------------------------------
        // 1. Load or create conversation
        // ------------------------------------------------------------
        CeConversation convo = conversationRepo
                .findById(conversationId)
                .orElseGet(() -> createNewConversation(conversationId));

        convo.setLastUserText(userText);
        convo.setUpdatedAt(OffsetDateTime.now());

        audit("USER_INPUT", conversationId,
                "{\"text\":\"" + escape(userText) + "\"}");

        String intent = convo.getIntentCode();
        String state = convo.getStateCode();
        String previousIntent = intent;

        // ------------------------------------------------------------
        // 2. Policy enforcement (hard stop)
        // ------------------------------------------------------------
        for (CePolicy policy : policyRepo.findByEnabledTrueOrderByPriorityAsc()) {
            if (matches(policy.getRuleType(), policy.getPattern(), userText)) {

                audit("POLICY_BLOCK", conversationId,
                        "{\"policyId\":" + policy.getPolicyId() +
                                ",\"ruleType\":\"" + escape(policy.getRuleType()) +
                                "\",\"pattern\":\"" + escape(policy.getPattern()) + "\"}");

                convo.setStatus("BLOCKED");
                convo.setLastAssistantJson(jsonText(policy.getResponseText()));
                convo.setUpdatedAt(OffsetDateTime.now());
                conversationRepo.save(convo);

                return new EngineResult(
                        intent,
                        state,
                        new TextPayload(policy.getResponseText()),
                        convo.getContextJson()
                );
            }
        }

        // ------------------------------------------------------------
        // 3. Intent classification (IDLE only)
        // ------------------------------------------------------------
        if ("IDLE".equalsIgnoreCase(state)) {

            String classifiedIntent = null;

            for (CeIntentClassifier ic : intentClassifierRepo.findByEnabledTrueOrderByPriorityAsc()) {

                if (matches(ic.getRuleType(), ic.getPattern(), userText)) {
                    classifiedIntent = ic.getIntentCode();
                    audit("INTENT_CLASSIFICATION_MATCHED", conversationId,
                            "{\"classifierId\":" + ic.getClassifierId() +
                                    ",\"intent\":\"" + escape(classifiedIntent) + "\"}");
                    break;
                }
            }

            if (classifiedIntent != null && (!classifiedIntent.equals(previousIntent))) {
                intent = classifiedIntent;
                convo.setIntentCode(intent);

                audit("INTENT_CLASSIFIED", conversationId,
                        "{\"intent\":\"" + escape(intent) + "\"}");
            }
        }

        // ------------------------------------------------------------
        // 4. Apply rules (userText) (override intent/state)
        // ------------------------------------------------------------
        RulePassResult pass1 = applyRules(conversationId, convo, userText, intent, state);
        intent = pass1.intent();
        state = pass1.state();

        if (pass1.shortCircuited() != null) {
            return pass1.shortCircuited();
        }

        // ------------------------------------------------------------
        // 5. Final fallbacks
        // ------------------------------------------------------------
        if (intent == null) {
            intent = "UNKNOWN";
            convo.setIntentCode(intent);
            audit("INTENT_FALLBACK", conversationId, "{\"intent\":\"UNKNOWN\"}");
        }

        if (state == null) {
            state = "IDLE";
            convo.setStateCode(state);
            audit("STATE_FALLBACK", conversationId, "{\"state\":\"IDLE\"}");
        }

        // ------------------------------------------------------------
        // 6. Extraction (intent + state bound schema) via ce_prompt_template
        // ------------------------------------------------------------
        final String beforeExtractionIntent = intent;
        final String beforeExtractionState = state;
        AtomicBoolean schemaComplete = new AtomicBoolean(false);
        AtomicReference<CeOutputSchema> ceOutputSchema = new AtomicReference<>();
        outputSchemaRepo
                .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                        beforeExtractionIntent, beforeExtractionState
                )
                .ifPresent(schema -> {
                    ceOutputSchema.set(schema);
                    audit("SCHEMA_EXTRACTION_START", conversationId,
                            "{\"schemaId\":" + schema.getSchemaId() + "}");

                    CePromptTemplate template = resolvePromptTemplate(
                            PURPOSE_SCHEMA_EXTRACTION,
                            beforeExtractionIntent
                    );

                    String systemPrompt = template.getSystemPrompt();

                    audit("PROMPT_SELECTED", conversationId,
                            "{\"purpose\":\"" + PURPOSE_SCHEMA_EXTRACTION + "\",\"templateId\":" + template.getTemplateId() +
                                    ",\"schemaId\":" + schema.getSchemaId() +
                                    ",\"systemPrompt\":\"" + escapeNewLine(systemPrompt) +
                                    "\",\"userPrompt\":\"" + escapeNewLine(template.getUserPrompt()) + "\"}");

                    String userPrompt = renderTemplate(
                            template.getUserPrompt(),
                            schema.getJsonSchema(),
                            convo.getContextJson(),
                            userText,
                            null
                    );

                    LlmInvocationContext.set(conversationId, beforeExtractionIntent, beforeExtractionState);

                    audit("SCHEMA_EXTRACTED", conversationId, schema.getJsonSchema());
                    audit("SCHEMA_EXTRACTION_LLM_INPUT", conversationId,
                            "{\"system_prompt\":\"" + escapeNewLine(systemPrompt) +
                                    "\",\"user_prompt\":\"" + escapeNewLine(userPrompt) +
                                    "\",\"schema\":\"" + escapeNewLine(schema.getJsonSchema()) +
                                    "\",\"userInput\":\"" + escapeNewLine(userText) + "\"}");

                    String extractedJson = llm().generateJson(
                            systemPrompt + "\n\n" + userPrompt,
                            schema.getJsonSchema(),
                            convo.getContextJson() + "\nUser input: " + userText
                    );

                    audit("SCHEMA_EXTRACTION_LLM_OUTPUT", conversationId, extractedJson);

                    String merged = JsonUtil.merge(convo.getContextJson(), extractedJson);
                    convo.setContextJson(merged);

                    schemaComplete.set(JsonUtil.isSchemaComplete(schema.getJsonSchema(), merged));

                    audit("SCHEMA_STATUS", conversationId,
                            "{\"schemaComplete\":" + schemaComplete +
                                    ",\"schema\":\"" + escapeNewLine(schema.getJsonSchema()) +
                                    "\",\"payload\":" + merged + "}");

                    // keep existing behavior (auto-advance)
                    audit("SCHEMA_MERGED", conversationId, merged);
                });

        // refresh in-memory intent/state after extraction block
        intent = convo.getIntentCode();
        state = convo.getStateCode();


        boolean shouldValidate = ceOutputSchema.get()!=null && JsonUtil.hasAnySchemaValue(
                convo.getContextJson(),
                ceOutputSchema.get().getJsonSchema()
        );

        if (!shouldValidate) {
            audit("VALIDATION_SKIPPED_NO_SCHEMA_INPUT", conversationId,
                    "{\"reason\":\"no schema fields present or couldn't be resolved\"}");
        }
        else {
            ValidationResult validationResult = validateUserData(engineContext, conversationId, convo, intent, state, ceOutputSchema.get(), userText, schemaComplete.get());
            intent = validationResult.intent();
            state = validationResult.state();
            if (validationResult.engineResult() != null) {
                return validationResult.engineResult();
            }
        }

        // ------------------------------------------------------------
        // 6.5 Validation via CCF-Core (DB-driven configs + DB-driven prompt + DB-driven rules)
        // ------------------------------------------------------------



        // ------------------------------------------------------------
        // 7. Resolve response (EXACT vs DERIVED) via ce_prompt_template for DERIVED
        // ------------------------------------------------------------
        final String resolvedIntent = intent;
        final String resolvedState = state;

        CeResponse resp =
                responseRepo
                        .findFirstByEnabledTrueAndStateCodeAndIntentCodeOrderByPriorityAsc(
                                resolvedState, resolvedIntent
                        )
                        .or(() ->
                                responseRepo.findFirstByEnabledTrueAndStateCodeAndIntentCodeIsNullOrderByPriorityAsc(
                                        resolvedState
                                )
                        )
                        .or(() ->
                                responseRepo.findFirstByEnabledTrueAndStateCodeOrderByPriorityAsc("ANY")
                        )
                        .orElseThrow(() ->
                                new IllegalStateException("No fallback response configured in ce_response")
                        );

        OutputPayload payload;

        if ("TEXT".equalsIgnoreCase(resp.getOutputFormat())
                && "EXACT".equalsIgnoreCase(resp.getResponseType())) {

            payload = new TextPayload(resp.getExactText());
            convo.setLastAssistantJson(jsonText(resp.getExactText()));

            audit("RESPONSE_EXACT", conversationId,
                    "{\"text\":\"" + escape(resp.getExactText()) + "\"}");
        }
        else if ("TEXT".equalsIgnoreCase(resp.getOutputFormat())) {

            CePromptTemplate template = resolvePromptTemplate(
                    PURPOSE_TEXT_RESPONSE,
                    resolvedIntent
            );

            String systemPrompt = template.getSystemPrompt();
            String userPrompt = renderTemplate(
                    template.getUserPrompt(),
                    null,
                    convo.getContextJson(),
                    userText,
                    null
            );

            audit("PROMPT_SELECTED", conversationId,
                    "{\"purpose\":\"" + PURPOSE_TEXT_RESPONSE + "\",\"templateId\":" + template.getTemplateId() + "}");

            LlmInvocationContext.set(conversationId, resolvedIntent, resolvedState);

            String text = llm().generateText(
                    systemPrompt + "\n\n" + userPrompt + "\n\n" + safe(resp.getDerivationHint()),
                    convo.getContextJson()
            );

            payload = new TextPayload(text);
            convo.setLastAssistantJson(jsonText(text));

            audit("RESPONSE_DERIVED_TEXT", conversationId,
                    "{\"text\":\"" + escapeNewLine(text) + "\"}");
        }
        else {

            CePromptTemplate template = resolvePromptTemplate(
                    PURPOSE_JSON_RESPONSE,
                    resolvedIntent
            );

            String systemPrompt = template.getSystemPrompt();
            String userPrompt = renderTemplate(
                    template.getUserPrompt(),
                    resp.getJsonSchema(),
                    convo.getContextJson(),
                    userText,
                    null
            );

            audit("PROMPT_SELECTED", conversationId,
                    "{\"purpose\":\"" + PURPOSE_JSON_RESPONSE + "\",\"templateId\":" + template.getTemplateId() + "}");

            LlmInvocationContext.set(conversationId, resolvedIntent, resolvedState);

            String json = llm().generateJson(
                    systemPrompt + "\n\n" + userPrompt + "\n\n" + safe(resp.getDerivationHint()),
                    resp.getJsonSchema(),
                    convo.getContextJson()
            );

            payload = new JsonPayload(json);
            convo.setLastAssistantJson(json);

            audit("RESPONSE_DERIVED_JSON", conversationId, json);
        }

        convo.setStatus("RUNNING");
        convo.setUpdatedAt(OffsetDateTime.now());
        conversationRepo.save(convo);

        audit("ENGINE_RETURN", conversationId,
                "{\"intent\":\"" + escape(intent) + "\",\"state\":\"" + escape(state) + "\"}");

        return new EngineResult(
                intent,
                state,
                payload,
                convo.getContextJson()
        );
    }

    private ValidationResult validateUserData(EngineContext engineContext, UUID conversationId, CeConversation convo, String intent, String state, CeOutputSchema ceOutputSchema, String userText, boolean schemaComplete) {
        String validationTablesJson = buildValidationTablesJson(engineContext, conversationId, convo, intent, state);

        if (validationTablesJson != null) {
            // store tables in context (optional but useful for UI/debug)
            try {
                String mergeJson = "{\"validation_tables\":" + validationTablesJson + "}";
                convo.setContextJson(JsonUtil.merge(convo.getContextJson(), mergeJson));
                audit("VALIDATION_TABLES_MERGED", conversationId, mergeJson);
            } catch (Exception e) {
                audit("VALIDATION_TABLES_MERGE_FAILED", conversationId,
                        "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }

            // sanitize context used for VALIDATION_DECISION so previous validation_decision cannot poison the next decision
            String validationContext = removeTopLevelField(convo.getContextJson(), "validation_decision");

            audit("VALIDATION_CONTEXT_SANITIZED", conversationId,
                    "{\"removed\":\"validation_decision\"}");

            CePromptTemplate template = resolvePromptTemplate(
                    PURPOSE_VALIDATION_DECISION,
                    intent
            );

            String systemPrompt = template.getSystemPrompt();
            String userPrompt = renderTemplate(
                    template.getUserPrompt(),
                    null,
                    validationContext,
                    userText,
                    validationTablesJson
            );

            audit("PROMPT_SELECTED", conversationId,
                    "{\"purpose\":\"" + PURPOSE_VALIDATION_DECISION + "\",\"templateId\":" + template.getTemplateId() + "}");

            audit("VALIDATION_DECISION_LLM_INPUT", conversationId,
                    "{\"system_prompt\":\"" + escapeNewLine(systemPrompt) +
                            "\",\"user_prompt\":\"" + escapeNewLine(userPrompt) +
                            "\",\"validation_tables\":\"" + escapeNewLine(validationTablesJson) + "\"}");

            LlmInvocationContext.set(conversationId, intent, state);

            String validationDecision = llm().generateText(
                    systemPrompt + "\n\n" + userPrompt,
                    validationContext
            );

            audit("VALIDATION_DECISION_LLM_OUTPUT", conversationId,
                    "{\"decision\":\"" + escapeNewLine(validationDecision) + "\"}");

            // store decision in context (optional)
            try {
                String mergeJson = "{\"validation_decision\":\"" + escapeJson(validationDecision) + "\"}";
                convo.setContextJson(JsonUtil.merge(convo.getContextJson(), mergeJson));
                audit("VALIDATION_DECISION_MERGED", conversationId, mergeJson);
            } catch (Exception e) {
                audit("VALIDATION_DECISION_MERGE_FAILED", conversationId,
                        "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }

            if (schemaComplete && "NEED_MORE_INFO".equalsIgnoreCase(convo.getStateCode())) {
                audit("SCHEMA_COMPLETE", conversationId,
                        "{\"schemaId\":" + ceOutputSchema.getSchemaId() + ",\"payload\":" + convo.getContextJson() + "}");

                convo.setStateCode("READY");
                state = "READY";
                audit("STATE_TRANSITION", conversationId,
                        "{\"from\":\"NEED_MORE_INFO\",\"to\":\"READY\"}");
            }
            if(!"READY".equalsIgnoreCase(convo.getStateCode())) {
                RulePassResult pass2 = applyRules(conversationId, convo, validationDecision, intent, state);
                intent = pass2.intent();
                state = pass2.state();
                if (pass2.shortCircuited() != null) {
                    return new ValidationResult(intent, state, pass2.shortCircuited());
                }
            }
        }
        return new ValidationResult(intent, state, null);
    }

    // ------------------------------------------------------------
    // Validation: build CCF-Core container responses JSON bundle (no decision logic here)
    // ------------------------------------------------------------
    private String buildValidationTablesJson(EngineContext engineContext, UUID conversationId, CeConversation convo, String intent, String state) {

        List<CeContainerConfig> configs =
                validationConfigRepo.findByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(intent, state);

        if (configs == null || configs.isEmpty()) {
            return null;
        }

        ObjectNode root = mapper.createObjectNode();

        for (CeContainerConfig cfg : configs) {

            String key = cfg.getInputParamName();
            Object value = extractValueFromContext(convo.getContextJson(), key);

            if (value == null) {
                audit("VALIDATION_SKIPPED_MISSING_INPUT", conversationId,
                        "{\"input_param_name\":\"" + escape(key) + "\"}");
                continue;
            }

            try {
                ContainerComponentRequest request = new ContainerComponentRequest();

                Map<String, Object> inputParams = new HashMap<>();
                inputParams.put(key, value);
                inputParams.putAll(engineContext.getInputParams() != null ? engineContext.getInputParams() : Collections.emptyMap());

                PageInfoRequest pageInfoRequest = PageInfoRequest
                                                    .builder()
                                                    .userId("convengine")
                                                    .loggedInUserId("convengine")
                                                    .pageId(cfg.getPageId())
                                                    .sectionId(cfg.getSectionId())
                                                    .containerId(cfg.getContainerId())
                                                    .inputParams(inputParams)
                                                    .build();
                pageInfoRequest = ceContainerInterceptor.intercept(pageInfoRequest);
                request.setPageInfo(List.of(pageInfoRequest));
                request.setRequestTypes(List.of(RequestType.CONTAINER));

                audit("VALIDATION_CCF_REQUEST", conversationId,
                        "{\"pageId\":" + cfg.getPageId() +
                                ",\"sectionId\":" + cfg.getSectionId() +
                                ",\"containerId\":" + cfg.getContainerId() +
                                ",\"input_param_name\":\"" + escape(key) +
                                "\",\"value\":\"" + escape(value+"") + "\"}");
                request = ceContainerInterceptor.intercept(request);
                ContainerComponentResponse resp = ccfCoreService.execute(request);

                JsonNode respNode = mapper.valueToTree(resp);
                root.set(key, respNode);

                audit("VALIDATION_CCF_RESPONSE", conversationId,
                        "{\"input_param_name\":\"" + escape(key) + "\",\"ccf\":\"" + escapeNewLine(respNode.toString()) + "\"}");

            } catch (Exception e) {
                audit("VALIDATION_CCF_FAILED", conversationId,
                        "{\"input_param_name\":\"" + escape(key) + "\",\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }

        if (root.isEmpty()) {
            return null;
        }

        return root.toString();
    }

    private Object extractValueFromContext(String contextJson, String key) {
        if (contextJson == null || contextJson.isBlank() || key == null || key.isBlank()) {
            return null;
        }

        try {
            JsonNode root = mapper.readTree(contextJson);
            JsonNode node = root.path(key);

            if (node.isMissingNode() || node.isNull()) {
                return null;
            }

            // String
            if (node.isTextual()) {
                return node.asText();
            }

            // Number (int, long, double)
            if (node.isNumber()) {
                return node.numberValue();
            }

            // Boolean (optional but safe)
            if (node.isBoolean()) {
                return node.asBoolean();
            }

            // Array of strings or numbers
            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                for (JsonNode element : node) {
                    if (element.isTextual()) {
                        values.add(element.asText());
                    } else if (element.isNumber()) {
                        values.add(element.numberValue());
                    } else if (element.isBoolean()) {
                        values.add(element.asBoolean());
                    }
                }
                return values;
            }

            // Object → return as Map (rare but safe)
            if (node.isObject()) {
                return mapper.convertValue(node, Map.class);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }


    // ------------------------------------------------------------
    // Rule pass (reusable): can run on userText OR validationDecision text
    // ------------------------------------------------------------
    private RulePassResult applyRules(UUID conversationId, CeConversation convo, String text, String intent, String state) {

        for (CeRule rule : ruleRepo.findByEnabledTrueOrderByPriorityAsc()) {

            if (!matches(rule.getRuleType(), rule.getMatchPattern(), text)) {
                continue;
            }

            if (rule.getIntentCode() != null && (!rule.getIntentCode().equalsIgnoreCase(intent))) {
                continue;
            }

            RuleAction action =
                    RuleAction.valueOf(rule.getAction().trim().toUpperCase());

            audit("RULE_MATCHED", conversationId,
                    "{\"ruleId\":" + rule.getRuleId() +
                            ",\"action\":\"" + escape(action.name()) + "\"}");

            switch (action) {

                case SET_INTENT -> {
                    intent = rule.getActionValue();
                    convo.setIntentCode(intent);
                    audit("SET_INTENT", conversationId,
                            "{\"ce_rule_id\":" + rule.getRuleId() + ",\"intent\":\"" + escape(intent) + "\", \"state\":\"" + escape(state) + "\"}");
                }

                case SET_STATE -> {
                    state = rule.getActionValue();
                    convo.setStateCode(state);
                    audit("SET_STATE", conversationId,
                            "{\"ce_rule_id\":" + rule.getRuleId() + ",\"state\":\"" + escape(state) + "\", \"intent\":\"" + escape(intent) + "\"}");
                }

                case SHORT_CIRCUIT -> {
                    convo.setLastAssistantJson(jsonText(rule.getActionValue()));
                    convo.setUpdatedAt(OffsetDateTime.now());
                    conversationRepo.save(convo);

                    audit("SHORT_CIRCUIT", conversationId,
                            "{\"ce_rule_id\":" + rule.getRuleId() + ",\"message\":\"" + escape(rule.getActionValue()) + "\", \"intent\":\"" + escape(intent) + "\", \"state\":\"" + escape(state) + "\"}");

                    EngineResult out = new EngineResult(
                            intent,
                            state,
                            new TextPayload(rule.getActionValue()),
                            convo.getContextJson()
                    );

                    return new RulePassResult(intent, state, out);
                }
            }
        }

        return new RulePassResult(intent, state, null);
    }

    private record RulePassResult(String intent, String state, EngineResult shortCircuited) {}

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    private CeConversation createNewConversation(UUID id) {
        return conversationRepo.save(
                CeConversation.builder()
                        .conversationId(id)
                        .status("RUNNING")
                        .stateCode("IDLE")
                        .contextJson("{}")
                        .createdAt(OffsetDateTime.now())
                        .updatedAt(OffsetDateTime.now())
                        .build()
        );
    }

    private boolean matches(String type, String pattern, String text) {
        if (type == null || pattern == null || text == null) {
            return false;
        }

        return switch (type.trim().toUpperCase()) {
            case "REGEX" ->
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                            .matcher(text)
                            .find();
            case "CONTAINS" ->
                    text.toLowerCase().contains(pattern.toLowerCase());
            case "STARTS_WITH" ->
                    text.toLowerCase().startsWith(pattern.toLowerCase());
            default -> false;
        };
    }

    private String jsonText(String text) {
        return "{\"type\":\"TEXT\",\"value\":\"" + escapeNewLine(text) + "\"}";
    }

    private void audit(String stage, UUID conversationId, String payloadJson) {
        auditRepo.save(
                CeAudit.builder()
                        .conversationId(conversationId)
                        .stage(stage)
                        .payloadJson(payloadJson)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }

    private CePromptTemplate resolvePromptTemplate(String purpose, String intentCode) {

        Optional<CePromptTemplate> intentSpecific =
                promptTemplateRepo.findFirstByEnabledTrueAndPurposeAndIntentCodeOrderByCreatedAtDesc(
                        purpose,
                        intentCode
                );

        return intentSpecific.orElseGet(() -> promptTemplateRepo
                .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(purpose)
                .orElseThrow(() ->
                        new IllegalStateException("No enabled ce_prompt_template found for purpose=" + purpose)
                ));
    }

    /**
     * Minimal templating:
     *  - {{schema}}
     *  - {{context}}
     *  - {{user_input}}
     *  - {{validation}} / {{validation_tables}}
     */
    private String renderTemplate(String template,
                                  String schemaJson,
                                  String contextJson,
                                  String userInput,
                                  String validationTablesJson) {

        String out = template == null ? "" : template;

        out = out.replace("{{context}}", safe(contextJson));
        out = out.replace("{{user_input}}", safe(userInput));

        out = out.replace("{{schema}}", Objects.requireNonNullElse(schemaJson, ""));

        if (validationTablesJson != null) {
            out = out.replace("{{validation}}", validationTablesJson);
            out = out.replace("{{validation_tables}}", validationTablesJson);
        } else {
            out = out.replace("{{validation}}", "");
            out = out.replace("{{validation_tables}}", "");
        }

        return out;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String escape(String s) {
        return JsonUtil.escape(s);
    }

    private String escapeNewLine(String s) {
        return JsonUtil.escape(s);
    }

    private String escapeJson(String s) {
        return JsonUtil.escape(s);
    }

    /**
     * Remove a top-level field from a JSON object string.
     * If parsing fails or it's not an object, returns the original string unchanged.
     */
    private String removeTopLevelField(String json, String fieldName) {
        if (json == null || json.isBlank() || fieldName == null || fieldName.isBlank()) {
            return json;
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;

            ObjectNode obj = (ObjectNode) root;
            obj.remove(fieldName);

            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private LlmClient llm() {
        return llmClient;
    }
}
