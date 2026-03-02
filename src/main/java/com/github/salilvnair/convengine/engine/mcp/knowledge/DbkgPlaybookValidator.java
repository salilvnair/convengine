package com.github.salilvnair.convengine.engine.mcp.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DbkgPlaybookValidator {

    private final DbkgSupportService support;

    public String validateGraph(List<Map<String, Object>> steps, List<Map<String, Object>> transitions) {
        if (steps.isEmpty()) {
            return "No steps defined.";
        }
        Map<String, Map<String, Object>> stepsByCode = indexStepsByCode(steps);
        List<String> explicitStarts = findExplicitStartSteps(steps);
        if (explicitStarts.size() > 1) {
            return "Multiple explicit start steps found: " + String.join(", ", explicitStarts);
        }
        if (transitions.isEmpty()) {
            return null;
        }

        Set<String> incoming = new HashSet<>();
        for (Map<String, Object> transition : transitions) {
            String from = support.asText(transition.get("fromStepCode"));
            String to = support.asText(transition.get("toStepCode"));
            if (from.isBlank()) {
                return "Transition has blank fromStepCode.";
            }
            if (!stepsByCode.containsKey(from)) {
                return "Transition references missing fromStepCode=" + from;
            }
            if (!to.isBlank()) {
                if (!stepsByCode.containsKey(to)) {
                    return "Transition references missing toStepCode=" + to;
                }
                incoming.add(to);
            }
        }

        List<String> startNodes = findImplicitStartSteps(steps, incoming);
        if (startNodes.isEmpty()) {
            return "No start step found; every step has an incoming transition.";
        }
        if (explicitStarts.isEmpty() && startNodes.size() > 1) {
            return "Multiple implicit start steps found: " + String.join(", ", startNodes)
                    + ". Mark exactly one step with config_json {\"isStart\":true}.";
        }
        if (!explicitStarts.isEmpty() && !startNodes.contains(explicitStarts.get(0))) {
            return "Explicit start step must be a graph root with no incoming transition: " + explicitStarts.get(0);
        }

        String chosenStart = explicitStarts.isEmpty() ? startNodes.get(0) : explicitStarts.get(0);
        Set<String> reachable = new HashSet<>();
        walkReachable(chosenStart, transitions, reachable);
        for (Map<String, Object> step : steps) {
            String stepCode = String.valueOf(step.get("stepCode"));
            if (!reachable.contains(stepCode)) {
                return "Unreachable step found: " + stepCode;
            }
        }
        return null;
    }

    public String resolveStartStepCode(List<Map<String, Object>> steps, List<Map<String, Object>> transitions) {
        if (steps.isEmpty()) {
            return null;
        }
        List<String> explicitStarts = findExplicitStartSteps(steps);
        if (!explicitStarts.isEmpty()) {
            return explicitStarts.get(0);
        }
        if (transitions.isEmpty()) {
            return String.valueOf(steps.get(0).get("stepCode"));
        }
        Set<String> incoming = new HashSet<>();
        for (Map<String, Object> transition : transitions) {
            String to = support.asText(transition.get("toStepCode"));
            if (!to.isBlank()) {
                incoming.add(to);
            }
        }
        List<String> roots = findImplicitStartSteps(steps, incoming);
        if (roots.isEmpty()) {
            return String.valueOf(steps.get(0).get("stepCode"));
        }
        return roots.get(0);
    }

    private Map<String, Map<String, Object>> indexStepsByCode(List<Map<String, Object>> steps) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            indexed.put(String.valueOf(step.get("stepCode")), step);
        }
        return indexed;
    }

    private List<String> findExplicitStartSteps(List<Map<String, Object>> steps) {
        List<String> explicitStarts = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> config = support.parseJsonObject(support.asText(step.get("configJson")));
            if (support.truthy(config.get("isStart"))) {
                explicitStarts.add(String.valueOf(step.get("stepCode")));
            }
        }
        return explicitStarts;
    }

    private List<String> findImplicitStartSteps(List<Map<String, Object>> steps, Set<String> incoming) {
        List<String> startNodes = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String stepCode = String.valueOf(step.get("stepCode"));
            if (!incoming.contains(stepCode)) {
                startNodes.add(stepCode);
            }
        }
        return startNodes;
    }

    private void walkReachable(String current, List<Map<String, Object>> transitions, Set<String> reachable) {
        if (current == null || current.isBlank() || !reachable.add(current)) {
            return;
        }
        for (Map<String, Object> transition : transitions) {
            String from = support.asText(transition.get("fromStepCode"));
            if (!current.equalsIgnoreCase(from)) {
                continue;
            }
            String to = support.asText(transition.get("toStepCode"));
            if (!to.isBlank()) {
                walkReachable(to, transitions, reachable);
            }
        }
    }
}
