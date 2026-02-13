package com.github.salilvnair.convengine.engine.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Getter
@Setter
@Slf4j
public class EngineSession {

    private final EngineContext engineContext;
    private final UUID conversationId;

    private CeConversation conversation;

    private String userText;
    private String intent;
    private String state;

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

    private final ObjectMapper mapper;
    private final List<StepTiming> stepTimings = new ArrayList<>();
    private Map<String, Object> inputParams = new LinkedHashMap<>();
    private Map<String, Object> safeInputParamsForOutput = new LinkedHashMap<>();


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
            initializeInputParams(engineContext.getInputParams());
        }
    }

    private void initializeInputParams(Map<String, Object> inputParams) {
        inputParams.forEach((s,v)->{
            this.inputParams.put(s, v);
            this.safeInputParamsForOutput.put(s, jsonSafe(v));
        });
    }

    // -------------------------------------------------
    // Sync
    // -------------------------------------------------

    public void syncFromConversation() {
        syncFromConversation(false);
        syncInputParamsFromConversation();
    }

    public void syncInputParamsFromConversation() {
        if (conversation == null) return;
        String inputParamsJson = conversation.getInputParamsJson();
        if (inputParamsJson == null || inputParamsJson.isBlank()) return;
        try {
            JsonNode root = mapper.readTree(inputParamsJson);
            if (!root.isObject()) return;
            Map<String, Object> params = mapper.convertValue(root, new TypeReference<>() {});
            initializeInputParams(params);
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void syncFromConversation(boolean preserveContext) {
        if (conversation == null) return;

        this.intent = conversation.getIntentCode();
        this.state = conversation.getStateCode();
        if (!preserveContext) {
            this.contextJson = conversation.getContextJson();
        }

        restoreClarificationFromContext();
    }

    public void syncToConversation() {
        if (conversation == null) return;

        persistClarificationToContext();

        conversation.setIntentCode(intent);
        conversation.setStateCode(state);
        conversation.setContextJson(contextJson);
    }

    // -------------------------------------------------
    // Clarification persistence (CRITICAL)
    // -------------------------------------------------

    private void persistClarificationToContext() {
        try {
            ObjectNode root =
                    contextJson == null || contextJson.isBlank()
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
            if (contextJson == null) return;

            JsonNode root = mapper.readTree(contextJson);
            JsonNode node = root.path("pending_clarification");

            if (node.isMissingNode()) return;

            this.pendingClarificationQuestion =
                    node.path("question").asText(null);
            this.pendingClarificationReason =
                    node.path("reason").asText(null);

        } catch (Exception e) {
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

            if (node.isMissingNode() || node.isNull()) return null;

            if (node.isTextual()) return node.asText();
            if (node.isNumber()) return node.numberValue();
            if (node.isBoolean()) return node.asBoolean();

            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                for (JsonNode e : node) {
                    if (e.isTextual()) values.add(e.asText());
                    else if (e.isNumber()) values.add(e.numberValue());
                    else if (e.isBoolean()) values.add(e.asBoolean());
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

    public Map<String, Object> schemaExtractedDataDict() {
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
        sessionMap.put("missingRequiredFields", missingRequiredFields);
        sessionMap.put("missingFieldOptions", missingFieldOptions);
        sessionMap.put("userText", userText);
        sessionMap.put("pendingClarificationQuestion", pendingClarificationQuestion);
        sessionMap.put("lastLlmStage", lastLlmStage);
        sessionMap.put("context", contextDict());
        sessionMap.put("schemaExtractedData", schemaExtractedDataDict());
        sessionMap.put("lastLlmOutput", extractLastLlmOutputForSession());
        return sessionMap;
    }

    private Object containerDataDict() {
        try {
            if (!containerData.isObject()) {
                return new LinkedHashMap<>();
            }
            return mapper.convertValue(containerData, new TypeReference<>() {});
        }
        catch (Exception e) {
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
            return mapper.convertValue(root, new TypeReference<>() {});
        }
        catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void putInputParam(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        inputParams.put(key, value);
        safeInputParamsForOutput.put(key, jsonSafe(value));
    }

    public Map<String, Object> safeInputParams() {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : safeInputParamsForOutput.entrySet()) {
            Object v = e.getValue();
            if("session".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), v);
        }

        return out;
    }

    private Object jsonSafe(Object value) {
        if (value == null) return null;
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
                    new TypeReference<Map<String, Object>>() {}
            );
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
        }
        catch (Exception e) {
            return null;
        }
    }

    public String ejectInputParamsJson() {
        try {
            return mapper.writeValueAsString(safeInputParams());
        }
        catch (Exception e) {
            return "{}";
        }
    }

    public void addPromptTemplateVars() {
        putInputParam("missing_fields", valueOrDefaultList(getInputParams().get("missing_fields")));
        putInputParam("missing_field_options", valueOrDefaultMap(getInputParams().get("missing_field_options")));
        putInputParam("schema_description", valueOrDefaultString(getInputParams().get("schema_description")));
        putInputParam("schema_field_details", valueOrDefaultMap(getInputParams().get("schema_field_details")));
        putInputParam("schema_id", getInputParams().getOrDefault("schema_id", null));
        putInputParam("schema_extracted_data", schemaExtractedDataDict());
        putInputParam("context", contextDict());
        putInputParam("session", sessionDict());
    }


    @SuppressWarnings("unchecked")
    public static List<String> valueOrDefaultList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
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
