package com.github.salilvnair.convengine.engine.rule.action.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;

import java.util.Objects;

public final class RuleConditionEvaluator {

    private static final Configuration JSON_PATH_CONFIG =
            Configuration.builder()
                    .options() // default: safe, no exceptions on missing
                    .build();

    private RuleConditionEvaluator() {}

    /**
     * Evaluates expressions like:
     *  $.needsClarification == true
     *  $.confidence >= 0.6
     */
    public static boolean evaluate(JsonNode payload, String expression) {
        if (payload == null || expression == null || expression.isBlank()) {
            return false;
        }

        try {
            // Split operator
            String op;
            if (expression.contains(">=")) op = ">=";
            else if (expression.contains("<=")) op = "<=";
            else if (expression.contains("==")) op = "==";
            else if (expression.contains(">")) op = ">";
            else if (expression.contains("<")) op = "<";
            else return false;

            String[] parts = expression.split(op);
            if (parts.length != 2) return false;

            String jsonPath = parts[0].trim();
            String expectedRaw = parts[1].trim();

            ReadContext ctx =
                    JsonPath.using(JSON_PATH_CONFIG).parse(payload.toString());

            Object actual = ctx.read(jsonPath);

            Object expected = parseLiteral(expectedRaw);

            return compare(actual, expected, op);

        } catch (Exception e) {
            return false; // rule failures must NEVER crash engine
        }
    }

    private static Object parseLiteral(String raw) {
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;

        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }

        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static boolean compare(Object actual, Object expected, String op) {
        if (actual == null) return false;

        if (actual instanceof Number a && expected instanceof Number e) {
            double av = a.doubleValue();
            double ev = e.doubleValue();

            return switch (op) {
                case "==" -> av == ev;
                case ">=" -> av >= ev;
                case "<=" -> av <= ev;
                case ">" -> av > ev;
                case "<" -> av < ev;
                default -> false;
            };
        }

        if (Objects.equals(actual, expected)) {
            return "==".equals(op);
        }

        return false;
    }
}
