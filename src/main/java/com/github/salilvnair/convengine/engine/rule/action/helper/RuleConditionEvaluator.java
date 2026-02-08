package com.github.salilvnair.convengine.engine.rule.action.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.salilvnair.convengine.util.JsonPathUtil;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleConditionEvaluator {

    private static final Pattern BINARY_PATTERN = Pattern.compile("^\\s*(\\$[^\\n<>=!]+?)\\s*(>=|<=|!=|==|>|<)\\s*(.+?)\\s*$");
    private static final Pattern SCALAR_FILTER_PATTERN = Pattern.compile("^\\s*\\$\\.([A-Za-z0-9_]+)\\[\\?\\(@\\s*(>=|<=|!=|==|>|<)\\s*(.+?)\\)\\]\\s*$");

    private RuleConditionEvaluator() {}

    public static boolean evaluate(JsonNode payload, String expression) {
        if (payload == null || expression == null || expression.isBlank()) {
            return false;
        }

        String expr = expression.trim();

        // 1) Real JSONPath search evaluation first (jayway)
        List<Object> directMatches = JsonPathUtil.search(payload, expr);
        if (!directMatches.isEmpty()) {
            if (directMatches.size() == 1 && directMatches.getFirst() instanceof Boolean b) {
                return b;
            }
            return true;
        }

        // 2) Legacy compatibility: "$.field[?(@ >= 0.6)]" where field is scalar
        Matcher scalarFilter = SCALAR_FILTER_PATTERN.matcher(expr);
        if (scalarFilter.matches()) {
            String field = scalarFilter.group(1).trim();
            String op = scalarFilter.group(2).trim();
            String rhs = scalarFilter.group(3).trim();
            Object actual = toScalar(payload.path(field));
            Object expected = parseLiteral(rhs);
            return compare(actual, expected, op);
        }

        // 3) Legacy compatibility: "$.confidence >= 0.6"
        Matcher binary = BINARY_PATTERN.matcher(expr);
        if (binary.matches()) {
            String jsonPath = binary.group(1).trim();
            String op = binary.group(2).trim();
            String rhs = binary.group(3).trim();
            List<Object> pathMatches = JsonPathUtil.search(payload, jsonPath);
            if (pathMatches.isEmpty()) {
                return false;
            }
            Object actual = pathMatches.getFirst();
            Object expected = parseLiteral(rhs);
            return compare(actual, expected, op);
        }

        // 4) Raw path existence
        try {
            return !JsonPathUtil.search(payload, expr).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static Object parseLiteral(String raw) {
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        if ("null".equalsIgnoreCase(raw)) return null;

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
                case "!=" -> av != ev;
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

        if ("!=".equals(op)) {
            return !Objects.equals(actual, expected);
        }

        return false;
    }

    private static Object toScalar(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        return node.toString();
    }
}
