package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class RegexRuleTypeResolver implements RuleTypeResolver {

    @Override
    public String type() {
        return "REGEX";
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        if (session.getUserText() == null || rule.getMatchPattern() == null) {
            return false;
        }
        return Pattern
                .compile(rule.getMatchPattern(), Pattern.CASE_INSENSITIVE)
                .matcher(session.getUserText())
                .find();
    }
}
