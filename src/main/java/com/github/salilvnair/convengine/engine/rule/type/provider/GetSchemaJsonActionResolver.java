package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
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
public class GetSchemaJsonActionResolver implements RuleActionResolver {
    private final AuditService audit;

    @Override
    public String action() {
        return RuleAction.GET_SCHEMA_JSON.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String key = (rule.getActionValue() == null || rule.getActionValue().isBlank())
                ? "schema_json"
                : rule.getActionValue();
        session.putInputParam(key, session.schemaJson());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("intent", session.getIntent());
        payload.put("state", session.getState());
        payload.put("schemaJson", session.schemaJson());
        payload.put("sessionInputParams", session.safeInputParams());
        audit.audit(RuleAction.GET_SCHEMA_JSON.name(), session.getConversationId(), payload);
    }
}
