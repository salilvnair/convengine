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

import java.util.LinkedHashMap;
import java.util.Map;

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
            if (rule.getMatchPattern() == null || rule.getMatchPattern().isBlank()) {
                return false;
            }
            JsonNode node = buildRuleFacts(session);
            return RuleConditionEvaluator.evaluate(
                    node,
                    rule.getMatchPattern()
            );
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode buildRuleFacts(EngineSession session) {
        try {
            Map<String, Object> facts = new LinkedHashMap<>(session.contextDict());
            facts.put("intent", session.getIntent());
            facts.put("state", session.getState());
            facts.put("schemaComplete", session.isSchemaComplete());
            facts.put("hasAnySchemaValue", session.isSchemaHasAnyValue());
            facts.put("missingRequiredFields", session.getMissingRequiredFields());
            facts.put("missingFieldOptions", session.getMissingFieldOptions());
            facts.put("userText", session.getUserText());
            facts.put("lastLlmOutput", session.getLastLlmOutput());
            facts.put("lastLlmStage", session.getLastLlmStage());
            facts.put("extractedData", session.extractedDataDict());

            if (session.getPayload() != null) {
                Object payload = switch (session.getPayload()) {
                    case JsonPayload(String json) -> JsonUtil.parseOrNull(json);
                    case TextPayload(String text) -> Map.of("text", text);
                    case null, default -> null;
                };
                if (payload != null) {
                    facts.put("payload", payload);
                }
            }

            return mapper.valueToTree(facts);
        }
        catch (Exception e) {
            return null;
        }
    }
}
