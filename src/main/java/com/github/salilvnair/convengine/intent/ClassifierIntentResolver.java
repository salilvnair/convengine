package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class ClassifierIntentResolver implements IntentResolver {

    private final StaticConfigurationCacheService staticCacheService;
    private final AuditService audit;

    @Override
    public String resolve(EngineSession session) {
        String userText = session.getUserText();
        UUID conversationId = session.getConversationId();
        Set<String> matchedIntents = new LinkedHashSet<>();
        Map<String, Object> matchedByRule = new LinkedHashMap<>();
        List<CeIntentClassifier> classifiers = staticCacheService.findEnabledIntentClassifiers();
        CeIntentClassifier firstMatchedClassifier = null;

        for (CeIntentClassifier ic : classifiers) {
            if (matches(ic.getRuleType(), ic.getPattern(), userText)) {
                if (firstMatchedClassifier == null) {
                    firstMatchedClassifier = ic;
                }
                String intent = ic.getIntentCode();
                matchedIntents.add(intent);
                matchedByRule.put(String.valueOf(ic.getClassifierId()), intent);
            }
        }

        if (matchedIntents.size() > 1) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.USER_TEXT, userText);
            payload.put(ConvEnginePayloadKey.MATCHED_INTENTS, matchedIntents);
            payload.put(ConvEnginePayloadKey.MATCHED_BY_RULE, matchedByRule);
            audit.audit(ConvEngineAuditStage.INTENT_CLASSIFIER_COLLISION, conversationId, payload);
            return null;
        }

        if (matchedIntents.size() == 1) {
            String intent = matchedIntents.iterator().next();
            String resolvedState = normalizeState(firstMatchedClassifier == null ? null : firstMatchedClassifier.getStateCode());
            session.setState(resolvedState);
            if (session.getConversation() != null) {
                session.getConversation().setStateCode(resolvedState);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, intent);
            payload.put(ConvEnginePayloadKey.STATE, resolvedState);
            payload.put(ConvEnginePayloadKey.MATCHED_BY_RULE, matchedByRule);
            audit.audit(ConvEngineAuditStage.INTENT_CLASSIFICATION_MATCHED, conversationId, payload);
            return intent;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.USER_TEXT, userText);
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
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

    private String normalizeState(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return "UNKNOWN";
        }
        return stateCode.trim().toUpperCase(Locale.ROOT);
    }
}
