package com.github.salilvnair.convengine.engine.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.springframework.stereotype.Component;

@Component
public class SessionContextHelper {

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectNode readRoot(EngineSession session) {
        try {
            String contextJson = session.getContextJson();
            if (contextJson == null || contextJson.isBlank()) {
                return mapper.createObjectNode();
            }
            JsonNode root = mapper.readTree(contextJson);
            if (root instanceof ObjectNode objectNode) {
                return objectNode;
            }
            return mapper.createObjectNode();
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    public void writeRoot(EngineSession session, ObjectNode root) {
        try {
            session.setContextJson(mapper.writeValueAsString(root == null ? mapper.createObjectNode() : root));
        } catch (Exception ignored) {
        }
    }

    public ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        if (parent == null || fieldName == null || fieldName.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode node = parent.get(fieldName);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = mapper.createObjectNode();
        parent.set(fieldName, created);
        return created;
    }
}

