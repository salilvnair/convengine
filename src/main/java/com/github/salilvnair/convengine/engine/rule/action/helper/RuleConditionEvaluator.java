package com.github.salilvnair.convengine.engine.rule.action.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.salilvnair.convengine.util.JsonPathUtil;
import com.jayway.jsonpath.TypeRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public final class RuleConditionEvaluator {

    private final JsonPathUtil jsonPathUtil;

    public boolean evaluate(JsonNode payload, String expression) {
        if (payload == null || expression == null || expression.isBlank()) {
            return false;
        }

        List<?> matches = jsonPathUtil.search(payload, expression.trim(), new TypeRef<>() {});
        if (matches.isEmpty()) {
            return false;
        }

        if (matches.size() == 1 && matches.getFirst() instanceof Boolean b) {
            return b;
        }

        return true;
    }
}
