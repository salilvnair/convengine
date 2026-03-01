package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineSyntaxConstants;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SetInputParamActionResolver implements RuleActionResolver {

    private final AuditService audit;
    private final ObjectMapper mapper;

    @Override
    public String action() {
        return RuleAction.SET_INPUT_PARAM.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        Map<String, Object> applied = new LinkedHashMap<>();
        for (Assignment assignment : parseAssignments(rule.getActionValue())) {
            session.putInputParam(assignment.key(), assignment.value());
            applied.put(assignment.key(), assignment.value());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.INPUT_PARAMS, session.safeInputParams());
        payload.put(ConvEnginePayloadKey.APPLIED_INPUT_PARAMS, applied);
        audit.audit(RuleAction.SET_INPUT_PARAM.name(), session.getConversationId(), payload);
    }

    private List<Assignment> parseAssignments(String actionValue) {
        String raw = actionValue == null ? "" : actionValue.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Action value for SET_INPUT_PARAM cannot be blank");
        }

        try {
            if (raw.startsWith(ConvEngineSyntaxConstants.JSON_ARRAY_PREFIX)) {
                List<Map<String, Object>> values = mapper.readValue(raw, new TypeReference<>() {
                });
                List<Assignment> out = new ArrayList<>();
                for (Map<String, Object> value : values) {
                    out.add(toAssignment(value));
                }
                return out;
            }
            if (raw.startsWith(ConvEngineSyntaxConstants.JSON_OBJECT_PREFIX)) {
                Map<String, Object> value = mapper.readValue(raw, new TypeReference<>() {
                });
                if (value.containsKey(ConvEnginePayloadKey.KEY)) {
                    return List.of(toAssignment(value));
                }
                List<Assignment> out = new ArrayList<>();
                for (Map.Entry<String, Object> entry : value.entrySet()) {
                    out.add(new Assignment(String.valueOf(entry.getKey()), entry.getValue()));
                }
                return out;
            }
        } catch (Exception ignored) {
            // Fall through to key:value parsing for backward-friendly input.
        }

        if (raw.contains(ConvEngineSyntaxConstants.KEY_VALUE_SEPARATOR)) {
            String[] parts = raw.split(ConvEngineSyntaxConstants.KEY_VALUE_SEPARATOR, 2);
            String key = parts[0] == null ? "" : parts[0].trim();
            if (key.isBlank()) {
                throw new IllegalArgumentException("SET_INPUT_PARAM key cannot be blank");
            }
            String value = parts.length > 1 ? parts[1].trim() : "";
            return List.of(new Assignment(key, coerceScalar(value)));
        }

        return List.of(new Assignment(raw, Boolean.TRUE));
    }

    private Assignment toAssignment(Map<String, Object> value) {
        Object key = value.get(ConvEnginePayloadKey.KEY);
        if (key == null || String.valueOf(key).isBlank()) {
            throw new IllegalArgumentException("SET_INPUT_PARAM requires a non-blank key");
        }
        return new Assignment(String.valueOf(key).trim(), value.get(ConvEnginePayloadKey.VALUE));
    }

    private Object coerceScalar(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (ConvEngineSyntaxConstants.NULL_LITERAL.equalsIgnoreCase(value)) {
            return null;
        }
        if (ConvEngineSyntaxConstants.BOOLEAN_TRUE.equalsIgnoreCase(value)) {
            return true;
        }
        if (ConvEngineSyntaxConstants.BOOLEAN_FALSE.equalsIgnoreCase(value)) {
            return false;
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private record Assignment(String key, Object value) {
    }
}
