package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.MatchTypeConstants;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.stereotype.Component;

@Component
public class ExactRuleTypeResolver implements RuleTypeResolver {
    @Override
    public String type() {
        return MatchTypeConstants.EXACT;
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        if (rule.getMatchPattern() == null || session.getUserText() == null) {
            return false;
        }
        if (ConvEngineValue.ANY.equalsIgnoreCase(rule.getMatchPattern().trim())) {
            return true;
        }
        return rule.getMatchPattern().trim().equalsIgnoreCase(session.getUserText().trim());
    }
}
