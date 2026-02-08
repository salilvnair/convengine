package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.stereotype.Component;

@Component
public class AgentRuleTypeResolver implements RuleTypeResolver {
    @Override
    public String type() {
        return "AGENT";
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        return true;
    }
}
