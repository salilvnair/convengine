package com.github.salilvnair.convengine.engine.mcp.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbkgOutcomeResolver {

    private final DbkgSupportService support;

    public Map<String, Object> resolveOutcome(String playbookCode, List<Map<String, Object>> stepResults, Map<String, Object> runtime) {
        List<Map<String, Object>> rules = support.readEnabledRows(support.cfg().getOutcomeRuleTable()).stream()
                .filter(row -> playbookCode.equalsIgnoreCase(support.asText(row.get("playbook_code"))))
                .sorted(java.util.Comparator.comparing(row -> support.parseInt(row.get("priority"), 100)))
                .toList();

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("rowCount", support.parseInt(runtime.get("lastRowCount"), 0));
        variables.put("requestRowCount", support.parseInt(runtime.get("requestRowCount"), 0));
        variables.put("placeholderSkipped", Boolean.TRUE.equals(runtime.get("placeholderSkipped")));
        variables.put("stepCount", stepResults.size());

        for (Map<String, Object> rule : rules) {
            String expr = support.asText(rule.get("condition_expr"));
            if (!evaluateCondition(expr, variables)) {
                continue;
            }
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("outcomeCode", rule.get("outcome_code"));
            outcome.put("severity", rule.get("severity"));
            outcome.put("explanation", renderTemplate(support.asText(rule.get("explanation_template")), variables));
            outcome.put("recommendedNextAction", renderTemplate(support.asText(rule.get("recommended_next_action")), variables));
            outcome.put("matchedCondition", expr);
            return outcome;
        }
        return null;
    }

    public String buildFallbackSummary(String playbookCode, List<Map<String, Object>> stepResults, Map<String, Object> runtime) {
        String summary = "Playbook " + playbookCode + " completed " + stepResults.size() + " step(s).";
        if (Boolean.TRUE.equals(runtime.get("placeholderSkipped"))) {
            summary += " Placeholder queries were skipped because consumer transaction mappings are still disabled.";
        } else if (runtime.containsKey("lastRowCount")) {
            summary += " The latest query returned " + runtime.get("lastRowCount") + " row(s).";
        }
        return summary;
    }

    public boolean evaluateCondition(String expr, Map<String, Object> variables) {
        String normalized = unwrapOuterParentheses(support.asText(expr).trim());
        if (normalized.isEmpty() || "true".equalsIgnoreCase(normalized)) {
            return true;
        }
        List<String> orParts = splitByLogicalOperator(normalized, "OR");
        if (orParts.size() > 1) {
            for (String part : orParts) {
                if (evaluateCondition(part, variables)) {
                    return true;
                }
            }
            return false;
        }
        List<String> andParts = splitByLogicalOperator(normalized, "AND");
        if (andParts.size() > 1) {
            for (String part : andParts) {
                if (!evaluateCondition(part, variables)) {
                    return false;
                }
            }
            return true;
        }
        return evaluateAtomicCondition(normalized, variables);
    }

    private boolean evaluateAtomicCondition(String normalized, Map<String, Object> variables) {
        String operator = null;
        for (String candidate : List.of(">=", "<=", ">", "<", "=")) {
            if (normalized.contains(candidate)) {
                operator = candidate;
                break;
            }
        }
        if (operator == null) {
            Object value = variables.get(normalized);
            return support.truthy(value);
        }

        String[] parts = normalized.split(java.util.regex.Pattern.quote(operator), 2);
        if (parts.length != 2) {
            return false;
        }
        Object left = variables.get(parts[0].trim());
        String rightRaw = parts[1].trim();
        if ("true".equalsIgnoreCase(rightRaw) || "false".equalsIgnoreCase(rightRaw)) {
            boolean leftBool = support.truthy(left);
            boolean rightBool = Boolean.parseBoolean(rightRaw);
            return "=".equals(operator) && leftBool == rightBool;
        }
        double leftNum = support.parseDouble(left, Double.NaN);
        double rightNum = support.parseDouble(rightRaw, Double.NaN);
        if (Double.isNaN(leftNum) || Double.isNaN(rightNum)) {
            String leftText = support.asText(left);
            String rightText = support.stripQuotes(rightRaw);
            return "=".equals(operator) && leftText.equalsIgnoreCase(rightText);
        }
        return switch (operator) {
            case ">" -> leftNum > rightNum;
            case "<" -> leftNum < rightNum;
            case ">=" -> leftNum >= rightNum;
            case "<=" -> leftNum <= rightNum;
            case "=" -> leftNum == rightNum;
            default -> false;
        };
    }

    private List<String> splitByLogicalOperator(String expr, String operator) {
        String spaced = " " + operator.toUpperCase() + " ";
        String upper = expr.toUpperCase();
        List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int lastStart = 0;
        for (int i = 0; i <= upper.length() - spaced.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && upper.startsWith(spaced, i)) {
                parts.add(expr.substring(lastStart, i).trim());
                lastStart = i + spaced.length();
                i = lastStart - 1;
            }
        }
        if (parts.isEmpty()) {
            return List.of(expr);
        }
        parts.add(expr.substring(lastStart).trim());
        return parts;
    }

    private String unwrapOuterParentheses(String expr) {
        String value = expr;
        while (value.startsWith("(") && value.endsWith(")") && enclosesWholeExpression(value)) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private boolean enclosesWholeExpression(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0 && i < expr.length() - 1) {
                    return false;
                }
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private String renderTemplate(String template, Map<String, Object> variables) {
        String out = support.asText(template);
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", support.asText(entry.getValue()));
        }
        return out;
    }
}
