package com.github.salilvnair.convengine.engine.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.OutputPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;

@Getter
@Setter
@Slf4j
public class EngineSession {

    private final EngineContext engineContext;
    private final UUID conversationId;

    private CeConversation conversation;

    private String userText;
    //this has information about conversation history so if user asks on top of a previous question
    // this will have the full question instead of just the followup.
    // This is useful in cases where we want to rewrite the query based
    // on conversation history and then use that rewritten query for intent detection and other things
    private String standaloneQuery;
    private String intent;
    private String state;
    private boolean intentLocked;
    private String intentLockReason;

    private String contextJson;
    private List<ConversationTurn> conversationHistory;

    private CeOutputSchema resolvedSchema;
    private boolean schemaComplete;
    private boolean schemaHasAnyValue;
    private String lastLlmOutput;
    private String lastLlmStage;
    private List<String> missingRequiredFields = new ArrayList<>();
    private Map<String, Object> missingFieldOptions = new LinkedHashMap<>();

    private String validationTablesJson;
    private String validationDecision;

    private OutputPayload payload;
    private String containerDataJson;
    private boolean hasContainerData;
    private JsonNode containerData;
    private EngineResult finalResult;

    private String pendingClarificationQuestion;
    private String pendingClarificationReason;

    private List<String> pendingClarificationQuestionHistory;
    private List<String> pendingClarificationReasonsHistory;

    // clarification state
    private boolean awaitingClarification;
    private int clarificationTurn;
    private String lastClarificationQuestion;

    private boolean queryRewritten;

    private final ObjectMapper mapper;
    private final List<StepTiming> stepTimings = new ArrayList<>();
    private Map<String, Object> inputParams = new LinkedHashMap<>();
    private Map<String, Object> safeInputParamsForOutput = new LinkedHashMap<>();
    private boolean postIntentRule;
    private String ruleExecutionSource;
    private String ruleExecutionOrigin;
    private Map<String, Object> systemExtensions = new LinkedHashMap<>();
    private Set<String> unknownSystemInputParamKeys = new LinkedHashSet<>();
    private Set<String> systemDerivedInputParamKeys = new LinkedHashSet<>();
    private Set<String> USER_PROMPT_KEYS = new LinkedHashSet<>();

    private static final Set<String> CONTROLLED_PROMPT_KEYS = Set.of(
            ConvEngineInputParamKey.MISSING_FIELDS,
            ConvEngineInputParamKey.MISSING_FIELD_OPTIONS,
            ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
            ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS,
            ConvEngineInputParamKey.SCHEMA_ID,
            ConvEngineInputParamKey.SCHEMA_JSON,
            ConvEngineInputParamKey.CONTEXT,
            ConvEngineInputParamKey.SESSION,
            ConvEngineInputParamKey.INTENT_SCORES,
            ConvEngineInputParamKey.INTENT_TOP3,
            ConvEngineInputParamKey.INTENT_COLLISION_CANDIDATES,
            ConvEngineInputParamKey.FOLLOWUPS,
            ConvEngineInputParamKey.AGENT_RESOLVER);
    private static final Pattern SAFE_INPUT_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{1,120}$");
    private static final Set<String> RESET_CONTROL_KEYS = Set.of("reset", "restart", "conversation_reset");

    public boolean hasPendingClarification() {
        return pendingClarificationQuestion != null && !pendingClarificationQuestion.isBlank();
    }

    public List<ConversationTurn> conversionHistory() {
        return conversationHistory == null ? Collections.emptyList() : conversationHistory;
    }

    public void clearClarification() {
        this.pendingClarificationQuestion = null;
        this.pendingClarificationReason = null;
    }

    public void addClarificationHistory() {
        if (pendingClarificationQuestionHistory == null) {
            pendingClarificationQuestionHistory = new ArrayList<>();
        }
        if (pendingClarificationReasonsHistory == null) {
            pendingClarificationReasonsHistory = new ArrayList<>();
        }
        pendingClarificationQuestionHistory.add(pendingClarificationQuestion);
        pendingClarificationReasonsHistory.add(pendingClarificationReason);
    }

    public EngineSession(EngineContext engineContext, ObjectMapper mapper) {
        this.engineContext = engineContext;
        this.mapper = mapper;
        this.conversationId = UUID.fromString(engineContext.getConversationId());
        this.userText = engineContext.getUserText();
        if (engineContext.getInputParams() != null) {
            mergeInputParams(engineContext.getInputParams(), true, false);
        }
    }

    private void mergeInputParams(Map<String, Object> params, boolean overwrite, boolean fromSystemWrite) {
        if (params == null || params.isEmpty()) {
            return;
        }
        params.forEach((key, value) -> mergeInputParam(key, value, overwrite, fromSystemWrite));
    }

    private void mergeInputParam(String key, Object value, boolean overwrite, boolean fromSystemWrite) {
        if (key == null || key.isBlank()) {
            return;
        }
        String normalizedKey = key.trim();
        USER_PROMPT_KEYS.add(normalizedKey);
        if (fromSystemWrite && !isControlledPromptKey(normalizedKey)) {
            systemExtensions.put(normalizedKey, value);
            unknownSystemInputParamKeys.add(normalizedKey);
            systemDerivedInputParamKeys.add(normalizedKey);
        }
        if (!overwrite && inputParams.containsKey(normalizedKey)) {
            return;
        }
        inputParams.put(normalizedKey, value);
        safeInputParamsForOutput.put(normalizedKey, jsonSafe(value));
    }

    private boolean isControlledPromptKey(String key) {
        return CONTROLLED_PROMPT_KEYS.contains(key) || USER_PROMPT_KEYS.contains(key);
    }

    // -------------------------------------------------
    // Sync
    // -------------------------------------------------

    public void syncFromConversation() {
        syncFromConversation(false);
        syncInputParamsFromConversation();
    }

    public void syncInputParamsFromConversation() {
        if (conversation == null)
            return;
        String inputParamsJson = conversation.getInputParamsJson();
        if (inputParamsJson == null || inputParamsJson.isBlank())
            return;
        try {
            JsonNode root = mapper.readTree(inputParamsJson);
            if (!root.isObject())
                return;
            Map<String, Object> params = mapper.convertValue(root, new TypeReference<>() {
            });
            mergeInputParams(params, false, false);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void syncFromConversation(boolean preserveContext) {
        if (conversation == null)
            return;

        this.intent = conversation.getIntentCode();
        this.state = conversation.getStateCode();
        if (!preserveContext) {
            this.contextJson = conversation.getContextJson();
        }

        restoreClarificationFromContext();
        restoreIntentLockFromContext();
    }

    public void syncToConversation() {
        if (conversation == null)
            return;

        persistClarificationToContext();
        persistIntentLockToContext();

        conversation.setIntentCode(intent);
        conversation.setStateCode(state);
        conversation.setContextJson(contextJson);
    }

    // -------------------------------------------------
    // Clarification persistence (CRITICAL)
    // -------------------------------------------------

    private void persistClarificationToContext() {
        try {
            ObjectNode root = contextJson == null || contextJson.isBlank()
                    ? mapper.createObjectNode()
                    : (ObjectNode) mapper.readTree(contextJson);

            ObjectNode clarification = mapper.createObjectNode();
            clarification.put("question", pendingClarificationQuestion);
            clarification.put("reason", pendingClarificationReason);

            root.set("pending_clarification", clarification);
            this.contextJson = mapper.writeValueAsString(root);

        } catch (Exception e) {
            // swallow — engine must not crash for context issues
        }
    }

    private void restoreClarificationFromContext() {
        try {
            if (contextJson == null)
                return;

            JsonNode root = mapper.readTree(contextJson);
            JsonNode node = root.path("pending_clarification");

            if (node.isMissingNode())
                return;

            this.pendingClarificationQuestion = node.path("question").asText(null);
            this.pendingClarificationReason = node.path("reason").asText(null);

        } catch (Exception e) {
            // ignore
        }
    }

    private void persistIntentLockToContext() {
        try {
            ObjectNode root = contextJson == null || contextJson.isBlank()
                    ? mapper.createObjectNode()
                    : (ObjectNode) mapper.readTree(contextJson);
            ObjectNode lock = mapper.createObjectNode();
            lock.put("locked", intentLocked);
            lock.put("reason", intentLockReason);
            root.set("intent_lock", lock);
            this.contextJson = mapper.writeValueAsString(root);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void restoreIntentLockFromContext() {
        try {
            if (contextJson == null || contextJson.isBlank()) {
                return;
            }
            JsonNode root = mapper.readTree(contextJson);
            JsonNode lock = root.path("intent_lock");
            if (lock.isMissingNode() || lock.isNull()) {
                return;
            }
            this.intentLocked = lock.path("locked").asBoolean(false);
            this.intentLockReason = lock.path("reason").asText(null);
        } catch (Exception ignored) {
            // ignore
        }
    }

    // -------------------------------------------------
    // Context helpers (unchanged)
    // -------------------------------------------------

    public Object extractValueFromContext(String key) {
        try {
            JsonNode root = this.getMapper().readTree(this.getContextJson());
            JsonNode node = root.path(key);

            if (node.isMissingNode() || node.isNull())
                return null;

            if (node.isTextual())
                return node.asText();
            if (node.isNumber())
                return node.numberValue();
            if (node.isBoolean())
                return node.asBoolean();

            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                for (JsonNode e : node) {
                    if (e.isTextual())
                        values.add(e.asText());
                    else if (e.isNumber())
                        values.add(e.numberValue());
                    else if (e.isBoolean())
                        values.add(e.asBoolean());
                }
                return values;
            }

            if (node.isObject()) {
                return this.getMapper().convertValue(node, Map.class);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> contextDict() {
        try {
            if (contextJson == null || contextJson.isBlank()) {
                return new LinkedHashMap<>();
            }
            JsonNode root = mapper.readTree(contextJson);
            if (!root.isObject()) {
                return new LinkedHashMap<>();
            }
            return mapper.convertValue(root, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public Map<String, Object> schemaJson() {
        Map<String, Object> context = contextDict();
        if (resolvedSchema == null || resolvedSchema.getJsonSchema() == null) {
            return context;
        }
        try {
            JsonNode schemaNode = mapper.readTree(resolvedSchema.getJsonSchema());
            JsonNode props = schemaNode.path("properties");
            if (!props.isObject()) {
                return context;
            }
            Map<String, Object> extracted = new LinkedHashMap<>();
            props.fieldNames().forEachRemaining(field -> {
                if (context.containsKey(field)) {
                    extracted.put(field, context.get(field));
                }
            });
            return extracted;
        } catch (Exception e) {
            return context;
        }
    }

    public Map<String, Object> sessionDict() {
        Map<String, Object> sessionMap = new LinkedHashMap<>();
        sessionMap.put("conversationId", String.valueOf(conversationId));
        sessionMap.put("intent", intent);
        sessionMap.put("state", state);
        sessionMap.put("schemaComplete", schemaComplete);
        sessionMap.put("hasContainerData", hasContainerData);
        sessionMap.put("containerData", containerDataDict());
        sessionMap.put("hasAnySchemaValue", schemaHasAnyValue);
        sessionMap.put("intentLocked", intentLocked);
        sessionMap.put("intentLockReason", intentLockReason);
        sessionMap.put("missingRequiredFields", missingRequiredFields);
        sessionMap.put("missingFieldOptions", missingFieldOptions);
        sessionMap.put("userText", userText);
        sessionMap.put("pendingClarificationQuestion", pendingClarificationQuestion);
        sessionMap.put("lastLlmStage", lastLlmStage);
        sessionMap.put("postIntentRule", postIntentRule);
        sessionMap.put("ruleExecutionSource", ruleExecutionSource);
        sessionMap.put("ruleExecutionOrigin", ruleExecutionOrigin);
        sessionMap.put("context", contextDict());
        sessionMap.put("schemaJson", schemaJson());
        sessionMap.put("lastLlmOutput", extractLastLlmOutputForSession());
        return sessionMap;
    }

    private Object containerDataDict() {
        try {
            if (!containerData.isObject()) {
                return new LinkedHashMap<>();
            }
            return mapper.convertValue(containerData, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> extractLastLlmOutputForSession() {
        try {
            if (lastLlmOutput == null || lastLlmOutput.isBlank()) {
                return new LinkedHashMap<>();
            }
            JsonNode root = mapper.readTree(lastLlmOutput);
            if (!root.isObject()) {
                return new LinkedHashMap<>();
            }
            return mapper.convertValue(root, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void putInputParam(String key, Object value) {
        mergeInputParam(key, value, true, true);
    }

    public void add(String key, Object value) {
        mergeInputParam(key, value, true, true);
    }

    public void put(String key, Object value) {
        mergeInputParam(key, value, true, true);
    }

    public Map<String, Object> promptTemplateVars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : inputParams.entrySet()) {
            if (isExposablePromptVar(e.getKey())) {
                vars.put(e.getKey(), e.getValue());
            }
        }
        return vars;
    }

    private boolean isExposablePromptVar(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return inputParams.containsKey(key);
    }

    public Map<String, Object> safeInputParams() {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : safeInputParamsForOutput.entrySet()) {
            String key = e.getKey();
            if (ConvEngineInputParamKey.SESSION.equalsIgnoreCase(key)
                    || ConvEngineInputParamKey.CONTEXT.equalsIgnoreCase(key)
                    || ConvEngineInputParamKey.SCHEMA_JSON.equalsIgnoreCase(key)
                    || ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS.equalsIgnoreCase(key)
                    || ConvEngineInputParamKey.MISSING_FIELD_OPTIONS.equalsIgnoreCase(key)
                    || ConvEngineInputParamKey.MCP_OBSERVATIONS.equalsIgnoreCase(key)) {
                continue;
            }
            out.put(key, e.getValue());
        }

        if (!unknownSystemInputParamKeys.isEmpty()) {
            out.put("droppedSystemPromptVars", new ArrayList<>(unknownSystemInputParamKeys));
        }
        return out;
    }

    private Object jsonSafe(Object value) {
        if (value == null)
            return null;
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map || value instanceof List) {
            return value;
        }
        // JsonNode → Map
        if (value instanceof JsonNode node) {
            return mapper.convertValue(
                    node,
                    new TypeReference<Map<String, Object>>() {
                    });
        }
        // fallback → string
        return String.valueOf(value);
    }

    public String inputParamAsString(String key) {
        Object value = inputParams.get(key);
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return JsonUtil.toJson(value);
    }

    public JsonNode eject() {
        try {
            Map<String, Object> facts = new LinkedHashMap<>(contextDict());
            facts.putAll(sessionDict());
            facts.put("inputParams", safeInputParams());
            if (getPayload() != null) {
                Object payload = switch (getPayload()) {
                    case JsonPayload(String json) -> JsonUtil.parseOrNull(json);
                    case TextPayload(String text) -> Map.of("text", text);
                };
                if (payload != null) {
                    facts.put("payload", payload);
                }
            }
            return new ObjectMapper().valueToTree(facts);
        } catch (Exception e) {
            return null;
        }
    }

    public String ejectInputParamsJson() {
        try {
            return mapper.writeValueAsString(safeInputParams());
        } catch (Exception e) {
            return "{}";
        }
    }

    public void addPromptTemplateVars() {
        putInputParam(ConvEngineInputParamKey.MISSING_FIELDS,
                valueOrDefaultList(inputParams.get(ConvEngineInputParamKey.MISSING_FIELDS)));
        putInputParam(ConvEngineInputParamKey.MISSING_FIELD_OPTIONS,
                valueOrDefaultMap(inputParams.get(ConvEngineInputParamKey.MISSING_FIELD_OPTIONS)));
        putInputParam(ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
                valueOrDefaultString(inputParams.get(ConvEngineInputParamKey.SCHEMA_DESCRIPTION)));
        putInputParam(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS,
                valueOrDefaultMap(inputParams.get(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS)));
        putInputParam(ConvEngineInputParamKey.SCHEMA_ID,
                inputParams.getOrDefault(ConvEngineInputParamKey.SCHEMA_ID, null));
        putInputParam(ConvEngineInputParamKey.SCHEMA_JSON, schemaJson());
        putInputParam(ConvEngineInputParamKey.CONTEXT, contextDict());
        putInputParam(ConvEngineInputParamKey.SESSION, sessionDict());
    }

    public void lockIntent(String reason) {
        this.intentLocked = true;
        this.intentLockReason = reason == null || reason.isBlank() ? "UNKNOWN" : reason;
    }

    public void unlockIntent() {
        this.intentLocked = false;
        this.intentLockReason = null;
    }

    public void resetForConversationRestart() {
        this.intent = "UNKNOWN";
        this.state = "UNKNOWN";
        this.contextJson = "{}";
        this.resolvedSchema = null;
        this.schemaComplete = false;
        this.schemaHasAnyValue = false;
        this.lastLlmOutput = null;
        this.lastLlmStage = null;
        this.missingRequiredFields = new ArrayList<>();
        this.missingFieldOptions = new LinkedHashMap<>();
        this.validationTablesJson = null;
        this.validationDecision = null;
        this.payload = null;
        this.containerDataJson = null;
        this.hasContainerData = false;
        this.containerData = null;
        this.finalResult = null;
        this.awaitingClarification = false;
        this.clarificationTurn = 0;
        this.lastClarificationQuestion = null;
        this.postIntentRule = false;
        this.ruleExecutionSource = null;
        this.ruleExecutionOrigin = null;
        this.pendingClarificationQuestionHistory = new ArrayList<>();
        this.pendingClarificationReasonsHistory = new ArrayList<>();
        clearClarification();
        unlockIntent();
        this.inputParams.clear();
        this.safeInputParamsForOutput.clear();
        this.systemExtensions.clear();
        this.unknownSystemInputParamKeys.clear();
        this.systemDerivedInputParamKeys.clear();
        this.USER_PROMPT_KEYS.clear();
        if (engineContext.getInputParams() != null) {
            Map<String, Object> requestParams = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : engineContext.getInputParams().entrySet()) {
                String key = e.getKey();
                if (key != null && RESET_CONTROL_KEYS.contains(key.trim().toLowerCase())) {
                    continue;
                }
                requestParams.put(e.getKey(), e.getValue());
            }
            mergeInputParams(requestParams, true, false);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> valueOrDefaultList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null)
                    out.add(String.valueOf(item));
            }
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> valueOrDefaultMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static String valueOrDefaultString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
