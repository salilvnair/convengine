package com.github.salilvnair.convengine.engine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.model.OutputPayload;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private CeOutputSchema resolvedSchema;
    private boolean schemaComplete;

    private String validationTablesJson;
    private String validationDecision;

    private OutputPayload payload;
    private String containerDataJson;
    private EngineResult finalResult;

    private final ObjectMapper mapper;

    private final List<StepTiming> stepTimings = new ArrayList<>();

    public EngineSession(EngineContext engineContext, ObjectMapper mapper) {
        this.engineContext = engineContext;
        this.mapper = mapper;
        this.conversationId = UUID.fromString(engineContext.getConversationId());
        this.userText = engineContext.getUserText();
    }

    public void syncFromConversation() {
        if (conversation == null) return;
        this.intent = conversation.getIntentCode();
        this.state = conversation.getStateCode();
        this.contextJson = conversation.getContextJson();
    }

    public void syncToConversation() {
        if (conversation == null) return;
        conversation.setIntentCode(intent);
        conversation.setStateCode(state);
        conversation.setContextJson(contextJson);
    }

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
}
