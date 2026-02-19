package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import com.github.salilvnair.convengine.repo.IntentClassifierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class ClassifierIntentResolver implements IntentResolver {

    private final IntentClassifierRepository intentClassifierRepo;
    private final AuditService audit;

    @Override
    public String resolve(EngineSession session) {
        String userText = session.getUserText();
        UUID conversationId = session.getConversationId();
        Set<String> matchedIntents = new LinkedHashSet<>();
        Map<String, Object> matchedByRule = new LinkedHashMap<>();

        for (CeIntentClassifier ic : intentClassifierRepo.findByEnabledTrueOrderByPriorityAsc()) {
            if (matches(ic.getRuleType(), ic.getPattern(), userText)) {
                String intent = ic.getIntentCode();
                matchedIntents.add(intent);
                matchedByRule.put(String.valueOf(ic.getClassifierId()), intent);
            }
        }

        if (matchedIntents.size() > 1) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.USER_TEXT, userText);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.MATCHED_INTENTS, matchedIntents);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.MATCHED_BY_RULE, matchedByRule);
            audit.audit(ConvEngineAuditStage.INTENT_CLASSIFIER_COLLISION, conversationId, payload);
            return null;
        }

        if (matchedIntents.size() == 1) {
            String intent = matchedIntents.iterator().next();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, intent);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.MATCHED_BY_RULE, matchedByRule);
            audit.audit(ConvEngineAuditStage.INTENT_CLASSIFICATION_MATCHED, conversationId, payload);
            return intent;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.USER_TEXT, userText);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.INTENT_CLASSIFIER_NO_MATCH, conversationId, payload);
        return null;
    }

    private boolean matches(String type, String pattern, String text) {
        if (type == null || pattern == null || text == null) return false;
        return switch (type.trim().toUpperCase()) {
            case "REGEX" -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
            case "CONTAINS" -> text.toLowerCase().contains(pattern.toLowerCase());
            case "STARTS_WITH" -> text.toLowerCase().startsWith(pattern.toLowerCase());
            default -> false;
        };
    }
}
