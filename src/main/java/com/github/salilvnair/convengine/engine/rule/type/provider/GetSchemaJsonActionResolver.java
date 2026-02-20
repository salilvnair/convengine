package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
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
                ? ConvEngineInputParamKey.SCHEMA_JSON
                : rule.getActionValue();
        session.putInputParam(key, session.schemaJson());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.KEY, key);
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.SCHEMA_JSON, session.schemaJson());
        payload.put(ConvEnginePayloadKey.SESSION_INPUT_PARAMS, session.safeInputParams());
        audit.audit(RuleAction.GET_SCHEMA_JSON.name(), session.getConversationId(), payload);
    }
}
