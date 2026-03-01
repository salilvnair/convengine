package com.github.salilvnair.convengine.engine.rule.action.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.util.JsonPathUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleConditionEvaluatorTest {

    private final RuleConditionEvaluator evaluator = new RuleConditionEvaluator(new JsonPathUtil());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluateReturnsBooleanWhenJsonPathProducesSingleBoolean() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("schemaComplete", true);

        boolean result = evaluator.evaluate(payload, "$[?(@.schemaComplete == true)]");

        assertTrue(result);
    }

    @Test
    void evaluateReturnsFalseWhenJsonPathFindsNothing() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("schemaComplete", false);

        boolean result = evaluator.evaluate(payload, "$[?(@.schemaComplete == true)]");

        assertFalse(result);
    }
}
