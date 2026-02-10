package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SetJsonActionResolver implements RuleActionResolver {
    private final AuditService audit;

    @Override
    public String action() {
        return RuleAction.SET_JSON.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String[] target = parseTarget(rule.getActionValue());
        String key = target[0];
        String path = target[1];

        JsonNode source = JsonUtil.parseOrNull(session.getLastLlmOutput());
        Object value = extractFromPath(source, path);
        session.putInputParam(key, value);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", rule.getRuleId());
        payload.put("key", key);
        payload.put("path", path);
        payload.put("intent", session.getIntent());
        payload.put("state", session.getState());
        payload.put("value", value);
        payload.put("sessionInputParams", session.auditInputParams());
        audit.audit(RuleAction.SET_JSON.name(), session.getConversationId(), payload);
    }

    private String[] parseTarget(String actionValue) {
        String raw = actionValue == null ? "" : actionValue.trim();
        if (raw.isBlank()) {
            return new String[]{"llm_json", ""};
        }
        if (raw.contains(":")) {
            String[] parts = raw.split(":", 2);
            String key = parts[0] == null || parts[0].isBlank() ? "llm_json" : parts[0].trim();
            String path = parts.length > 1 ? parts[1].trim() : "";
            return new String[]{key, path};
        }
        return new String[]{raw, raw};
    }

    private Object extractFromPath(JsonNode source, String path) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return toObject(source);
        }

        String target = path.trim();
        if (target.startsWith("$.")) {
            target = target.substring(2);
        }
        String[] parts = target.split("\\.");
        JsonNode current = source;
        for (String part : parts) {
            String key = part == null ? "" : part.trim();
            if (key.isBlank()) continue;
            if (current == null || !current.isObject() || !current.has(key)) {
                return null;
            }
            current = current.get(key);
        }
        return toObject(current);
    }

    private Object toObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        try {
            return JsonUtil.fromJson(node.toString(), Object.class);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
