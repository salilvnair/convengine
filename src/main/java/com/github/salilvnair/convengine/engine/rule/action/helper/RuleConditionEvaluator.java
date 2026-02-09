package com.github.salilvnair.convengine.engine.rule.action.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.salilvnair.convengine.util.JsonPathUtil;

import java.util.List;

public final class RuleConditionEvaluator {

    private RuleConditionEvaluator() {}

    public static boolean evaluate(JsonNode payload, String expression) {
        if (payload == null || expression == null || expression.isBlank()) {
            return false;
        }

        List<Object> matches = JsonPathUtil.search(payload, expression.trim());
        if (matches.isEmpty()) {
            return false;
        }

        if (matches.size() == 1 && matches.getFirst() instanceof Boolean b) {
            return b;
        }

        return true;
    }
}
