package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SetIntentActionResolver implements RuleActionResolver {
    private final AuditService audit;

    @Override
    public String action() {
        return RuleAction.SET_INTENT.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String previousIntent = session.getIntent();
        session.setIntent(rule.getActionValue());
        session.getConversation().setIntentCode(rule.getActionValue());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.FROM_INTENT, previousIntent);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TO_INTENT, rule.getActionValue());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.CONTEXT, session.contextDict());
        audit.audit(RuleAction.SET_INTENT.name(), session.getConversationId(), payload);
    }
}
