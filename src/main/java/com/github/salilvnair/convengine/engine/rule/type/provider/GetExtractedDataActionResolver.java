package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class GetExtractedDataActionResolver implements RuleActionResolver {
    private final AuditService audit;

    @Override
    public String action() {
        return "GET_EXTRACTED_DATA";
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String key = (rule.getActionValue() == null || rule.getActionValue().isBlank())
                ? "extracted_data"
                : rule.getActionValue();
        session.putInputParam(key, session.extractedDataDict());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("intent", session.getIntent());
        payload.put("state", session.getState());
        payload.put("extractedData", session.extractedDataDict());
        audit.audit("GET_EXTRACTED_DATA", session.getConversationId(), payload);
    }
}
