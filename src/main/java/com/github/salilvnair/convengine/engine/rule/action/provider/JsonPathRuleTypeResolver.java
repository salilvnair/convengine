package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.rule.action.helper.RuleConditionEvaluator;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import org.springframework.stereotype.Component;

@Component
public class JsonPathRuleTypeResolver implements RuleTypeResolver {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "JSON_PATH";
    }

    @Override
    public boolean resolve(EngineSession session, CeRule rule) {
        try {
            if (session.getPayload() == null) return false;
            JsonNode node = extractPayloadJson(session);
            return RuleConditionEvaluator.evaluate(
                    node,
                    rule.getMatchPattern()
            );
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode extractPayloadJson(EngineSession session) {
        try {
            return switch (session.getPayload()) {
                case JsonPayload(String json) -> mapper.readTree(json);
                case TextPayload(String text) ->
                    // Wrap text payload so rules can still inspect fields if needed
                        mapper.readTree(
                                "{\"text\":\"" + JsonUtil.escape(text) + "\"}"
                        );
                case null, default -> null;
            };
        }
        catch (Exception e) {
            return null;
        }
    }
}
