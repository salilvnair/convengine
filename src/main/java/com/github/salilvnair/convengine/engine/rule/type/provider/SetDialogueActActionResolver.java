package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SetDialogueActActionResolver implements RuleActionResolver {

    private final AuditService audit;
    private final ObjectMapper mapper;

    @Override
    public String action() {
        return RuleAction.SET_DIALOGUE_ACT.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        Assignment assignment = parseAssignment(rule == null ? null : rule.getActionValue());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT, assignment.dialogueAct().name());
        if (assignment.confidence() != null) {
            session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_CONFIDENCE, assignment.confidence());
        }
        if (assignment.source() != null && !assignment.source().isBlank()) {
            session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_SOURCE, assignment.source().trim());
        }
        if (assignment.hasStandaloneQuery()) {
            session.setStandaloneQuery(assignment.standaloneQuery());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.RULE_ID, rule == null ? null : rule.getRuleId());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT, assignment.dialogueAct().name());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_CONFIDENCE, assignment.confidence());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_SOURCE, assignment.source());
        payload.put(ConvEnginePayloadKey.STANDALONE_QUERY, assignment.standaloneQuery());
        audit.audit(RuleAction.SET_DIALOGUE_ACT.name(), session.getConversationId(), payload);
    }

    private Assignment parseAssignment(String actionValue) {
        String raw = actionValue == null ? "" : actionValue.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Action value for SET_DIALOGUE_ACT cannot be blank");
        }
        if (raw.startsWith("{")) {
            try {
                Map<String, Object> parsed = mapper.readValue(raw, new TypeReference<>() {
                });
                return fromMap(parsed);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON for SET_DIALOGUE_ACT", e);
            }
        }
        DialogueAct act = DialogueAct.valueOf(raw.toUpperCase());
        return new Assignment(act, null, null, null, false);
    }

    private Assignment fromMap(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            throw new IllegalArgumentException("SET_DIALOGUE_ACT JSON cannot be empty");
        }
        Object actRaw = parsed.getOrDefault(ConvEnginePayloadKey.DIALOGUE_ACT, parsed.get(ConvEngineInputParamKey.DIALOGUE_ACT));
        if (actRaw == null || String.valueOf(actRaw).isBlank()) {
            throw new IllegalArgumentException("SET_DIALOGUE_ACT requires dialogueAct");
        }
        DialogueAct act = DialogueAct.valueOf(String.valueOf(actRaw).trim().toUpperCase());
        Double confidence = parsed.containsKey(ConvEnginePayloadKey.CONFIDENCE)
                ? coerceDouble(parsed.get(ConvEnginePayloadKey.CONFIDENCE))
                : null;
        Object sourceRaw = parsed.getOrDefault(ConvEnginePayloadKey.SOURCE, parsed.get(ConvEnginePayloadKey.DIALOGUE_ACT_SOURCE));
        String source = sourceRaw == null ? null : String.valueOf(sourceRaw);
        boolean hasStandaloneQuery = parsed.containsKey(ConvEnginePayloadKey.STANDALONE_QUERY)
                || parsed.containsKey(ConvEngineInputParamKey.STANDALONE_QUERY);
        Object standaloneQueryRaw = parsed.containsKey(ConvEnginePayloadKey.STANDALONE_QUERY)
                ? parsed.get(ConvEnginePayloadKey.STANDALONE_QUERY)
                : parsed.get(ConvEngineInputParamKey.STANDALONE_QUERY);
        String standaloneQuery = standaloneQueryRaw == null ? null : String.valueOf(standaloneQueryRaw);
        if (standaloneQuery != null && standaloneQuery.isBlank()) {
            standaloneQuery = null;
        }
        return new Assignment(act, confidence, source, standaloneQuery, hasStandaloneQuery);
    }

    private Double coerceDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private record Assignment(
            DialogueAct dialogueAct,
            Double confidence,
            String source,
            String standaloneQuery,
            boolean hasStandaloneQuery) {
    }
}
