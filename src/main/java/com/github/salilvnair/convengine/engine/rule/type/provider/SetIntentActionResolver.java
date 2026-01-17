package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.stereotype.Component;

@Component
public class SetIntentActionResolver implements RuleActionResolver {

    @Override
    public String action() {
        return "SET_INTENT";
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        session.setIntent(rule.getActionValue());
        session.getConversation().setIntentCode(rule.getActionValue());
    }
}
