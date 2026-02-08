package com.github.salilvnair.convengine.engine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.model.OutputPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
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
    private EngineResult finalResult;

    // ✅ NEW — clarification memory
    private String pendingClarificationQuestion;
    private String pendingClarificationReason;

    // clarification state
    private boolean awaitingClarification;
    private int clarificationTurn;
    private String lastClarificationQuestion;


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

    private final ObjectMapper mapper;
    private final List<StepTiming> stepTimings = new ArrayList<>();
    private Map<String, Object> inputParams = new LinkedHashMap<>();

    public EngineSession(EngineContext engineContext, ObjectMapper mapper) {
        this.engineContext = engineContext;
        this.mapper = mapper;
        this.conversationId = UUID.fromString(engineContext.getConversationId());
        this.userText = engineContext.getUserText();
        if (engineContext.getInputParams() != null) {
            this.inputParams.putAll(engineContext.getInputParams());
        }
    }

    // -------------------------------------------------
    // Sync
    // -------------------------------------------------

    public void syncFromConversation() {
        syncFromConversation(false);
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
            return mapper.convertValue(root, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public Map<String, Object> extractedDataDict() {
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
        sessionMap.put("hasAnySchemaValue", schemaHasAnyValue);
        sessionMap.put("missingRequiredFields", missingRequiredFields);
        sessionMap.put("missingFieldOptions", missingFieldOptions);
        sessionMap.put("userText", userText);
        sessionMap.put("pendingClarificationQuestion", pendingClarificationQuestion);
        sessionMap.put("lastLlmStage", lastLlmStage);
        sessionMap.put("context", contextDict());
        sessionMap.put("extractedData", extractedDataDict());
        return sessionMap;
    }

    public void putInputParam(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        inputParams.put(key, value);
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

}
