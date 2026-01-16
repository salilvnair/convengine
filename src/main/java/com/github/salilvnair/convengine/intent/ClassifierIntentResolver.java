package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import com.github.salilvnair.convengine.repo.IntentClassifierRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class ClassifierIntentResolver implements IntentResolver {

    private final IntentClassifierRepository intentClassifierRepo;
    private final AuditService audit;

    @Override
    public String resolve(EngineSession session) {
        String state = session.getState();
        if (!"IDLE".equalsIgnoreCase(state)) return null;
        String userText = session.getUserText();
        UUID conversationId = session.getConversationId();

        for (CeIntentClassifier ic : intentClassifierRepo.findByEnabledTrueOrderByPriorityAsc()) {
            if (matches(ic.getRuleType(), ic.getPattern(), userText)) {
                String intent = ic.getIntentCode();
                audit.audit("INTENT_CLASSIFICATION_MATCHED", conversationId,
                        "{\"classifierId\":" + ic.getClassifierId() +
                                ",\"intent\":\"" + JsonUtil.escape(intent) + "\"}");
                return intent;
            }
        }
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
