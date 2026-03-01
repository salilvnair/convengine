package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.constants.MatchTypeConstants;
import com.github.salilvnair.convengine.engine.rule.action.helper.RuleConditionEvaluator;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonPathRuleTypeResolver implements RuleTypeResolver {
    private final RuleConditionEvaluator ruleConditionEvaluator;
    @Override
    public String type() {
        return MatchTypeConstants.JSON_PATH;
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        try {
            if (rule.getMatchPattern() == null || rule.getMatchPattern().isBlank()) {
                return false;
            }
            JsonNode node = session.eject();
            return ruleConditionEvaluator.evaluate(
                    node,
                    rule.getMatchPattern()
            );
        } catch (Exception e) {
            return false;
        }
    }
}
