package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
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
public class SetStateActionResolver implements RuleActionResolver {
    private final AuditService audit;

    @Override
    public String action() {
        return RuleAction.SET_STATE.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String previousState = session.getState();
        session.setState(rule.getActionValue());
        session.getConversation().setStateCode(rule.getActionValue());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(ConvEnginePayloadKey.FROM_STATE, previousState);
        payload.put(ConvEnginePayloadKey.TO_STATE, rule.getActionValue());
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
        audit.audit(RuleAction.SET_STATE.name(), session.getConversationId(), payload);
    }
}
