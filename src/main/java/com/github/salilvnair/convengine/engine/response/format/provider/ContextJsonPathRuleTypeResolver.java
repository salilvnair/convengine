package com.github.salilvnair.convengine.engine.response.format.provider;

import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;

@Component
public class ContextJsonPathRuleTypeResolver implements RuleTypeResolver {

    @Override
    public String type() {
        return "CONTEXT_JSON_PATH";
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        try {
            String contextJson = session.getContextJson();
            if (contextJson == null || contextJson.isBlank()) {
                return false;
            }

            Object value = JsonPath.read(contextJson, rule.getMatchPattern());
            return value != null;

        } catch (Exception e) {
            return false;
        }
    }
}
