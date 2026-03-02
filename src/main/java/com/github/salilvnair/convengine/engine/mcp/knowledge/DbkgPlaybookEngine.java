package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.engine.session.EngineSession;
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
public class DbkgPlaybookEngine {

    private final DbkgPlaybookResolver playbookResolver;
    private final DbkgSupportService support;
    private final DbkgStepExecutorFactory stepExecutorFactory;
    private final DbkgOutcomeResolver outcomeResolver;
    private final DbkgPlaybookValidator playbookValidator;

    public Map<String, Object> executeInvestigation(Map<String, Object> args, EngineSession session) {
        Map<String, Object> plan = playbookResolver.planInvestigation(args, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPlaybook = (Map<String, Object>) plan.get(DbkgConstants.KEY_SELECTED_PLAYBOOK);
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedCase = (Map<String, Object>) plan.get(DbkgConstants.KEY_SELECTED_CASE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.getOrDefault(DbkgConstants.KEY_STEPS, List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) plan.getOrDefault(DbkgConstants.KEY_TRANSITIONS, List.of());

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put(DbkgConstants.KEY_QUESTION, plan.get(DbkgConstants.KEY_QUESTION));
        execution.put(DbkgConstants.KEY_SELECTED_CASE, selectedCase);
        execution.put(DbkgConstants.KEY_SELECTED_PLAYBOOK, selectedPlaybook);
        execution.put(DbkgConstants.KEY_API_FLOWS, plan.get(DbkgConstants.KEY_API_FLOWS));
        execution.put(DbkgConstants.KEY_TRANSITIONS, transitions);

        if (selectedPlaybook == null || steps.isEmpty()) {
            execution.put(DbkgConstants.KEY_STEPS_EXECUTED, List.of());
            execution.put(DbkgConstants.KEY_OUTCOME, null);
            execution.put(DbkgConstants.KEY_FINAL_SUMMARY, DbkgConstants.MESSAGE_NO_PLAYBOOK_RESOLVED);
            return execution;
        }

        String graphValidationError = playbookValidator.validateGraph(steps, transitions);
        if (graphValidationError != null) {
            execution.put(DbkgConstants.KEY_STEPS_EXECUTED, List.of());
            execution.put(DbkgConstants.KEY_OUTCOME, null);
            execution.put(DbkgConstants.KEY_DAG_ERROR, graphValidationError);
            execution.put(DbkgConstants.KEY_FINAL_SUMMARY, DbkgConstants.MESSAGE_PLAYBOOK_INVALID_PREFIX + graphValidationError);
            return execution;
        }

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("conversationId", session == null ? null : session.getConversationId());
        runtime.put("session", session);
        runtime.put(DbkgConstants.KEY_ARGS, args == null ? Map.of() : new LinkedHashMap<>(args));
        runtime.put(DbkgConstants.KEY_QUESTION, plan.get(DbkgConstants.KEY_QUESTION));
        runtime.put(DbkgConstants.KEY_CASE, selectedCase == null ? Map.of() : selectedCase);
        runtime.put(DbkgConstants.KEY_PLAYBOOK, selectedPlaybook);
        runtime.put(DbkgConstants.KEY_API_FLOWS, plan.getOrDefault(DbkgConstants.KEY_API_FLOWS, List.of()));
        runtime.put(DbkgConstants.KEY_STEP_OUTPUTS, new LinkedHashMap<String, Object>());
        runtime.put(DbkgConstants.KEY_PLACEHOLDER_SKIPPED, false);
        runtime.put(DbkgConstants.KEY_EXECUTION_MODE, transitions.isEmpty()
                ? DbkgConstants.EXECUTION_MODE_SEQUENCE_FALLBACK
                : DbkgConstants.EXECUTION_MODE_TRANSITION_DAG);

        List<Map<String, Object>> stepResults = new ArrayList<>();
        Map<String, Map<String, Object>> stepsByCode = indexStepsByCode(steps);
        String currentStepCode = playbookValidator.resolveStartStepCode(steps, transitions);
        Set<String> visited = new HashSet<>();
        int maxIterations = Math.max(steps.size() * 2, 1);
        int iterations = 0;

        while (currentStepCode != null && iterations < maxIterations) {
            Map<String, Object> step = stepsByCode.get(currentStepCode);
            if (step == null) {
                runtime.put(DbkgConstants.KEY_DAG_ERROR, "Missing step definition for stepCode=" + currentStepCode);
                break;
            }
            if (!visited.add(currentStepCode)) {
                runtime.put(DbkgConstants.KEY_DAG_ERROR, "Cycle detected at stepCode=" + currentStepCode);
                break;
            }
            Map<String, Object> stepResult = executeStep(step, runtime);
            stepResults.add(stepResult);
            support.stepOutputs(runtime).put(String.valueOf(step.get(DbkgConstants.KEY_STEP_CODE)), stepResult.get(DbkgConstants.KEY_OUTPUT));
            if (Boolean.TRUE.equals(stepResult.get(DbkgConstants.KEY_HALTED))) {
                break;
            }
            currentStepCode = resolveNextStepCode(currentStepCode, stepResult, runtime, transitions);
            iterations++;
            if (transitions.isEmpty() && currentStepCode == null) {
                currentStepCode = nextBySequence(stepResults, steps);
            }
        }
        if (iterations >= maxIterations && currentStepCode != null) {
            runtime.put(DbkgConstants.KEY_DAG_ERROR, "Max iteration limit reached before DAG completed.");
        }

        String playbookCode = String.valueOf(selectedPlaybook.get("playbookCode"));
        Map<String, Object> outcome = outcomeResolver.resolveOutcome(playbookCode, stepResults, runtime);

        execution.put(DbkgConstants.KEY_STEPS_EXECUTED, stepResults);
        execution.put(DbkgConstants.KEY_OUTCOME, outcome);
        execution.put(DbkgConstants.KEY_DAG_ERROR, runtime.get(DbkgConstants.KEY_DAG_ERROR));
        execution.put(DbkgConstants.KEY_FINAL_SUMMARY, outcome == null
                ? outcomeResolver.buildFallbackSummary(playbookCode, stepResults, runtime)
                : outcome.get("explanation"));
        execution.put(DbkgConstants.KEY_PLACEHOLDER_SKIPPED, runtime.get(DbkgConstants.KEY_PLACEHOLDER_SKIPPED));
        return execution;
    }

    private Map<String, Object> executeStep(Map<String, Object> step, Map<String, Object> runtime) {
        String stepCode = String.valueOf(step.get("stepCode"));
        String executorCode = support.asText(step.get("executorCode"));
        String templateCode = support.asText(step.get("templateCode"));
        Map<String, Object> config = support.parseJsonObject(support.asText(step.get("configJson")));

        Map<String, Object> output;
        boolean halted = false;
        String status = DbkgConstants.STATUS_SUCCESS;
        try {
            output = stepExecutorFactory.require(executorCode).execute(stepCode, templateCode, config, runtime);
            if (Boolean.TRUE.equals(output.get(DbkgConstants.KEY_PLACEHOLDER_SKIPPED))) {
                runtime.put(DbkgConstants.KEY_PLACEHOLDER_SKIPPED, true);
                status = DbkgConstants.STATUS_PLACEHOLDER_SKIPPED;
            }
        } catch (Exception e) {
            output = Map.of(
                    DbkgConstants.KEY_STATUS, DbkgConstants.STATUS_ERROR,
                    DbkgConstants.KEY_ERROR, String.valueOf(e.getMessage()));
            status = DbkgConstants.STATUS_ERROR;
            halted = support.truthy(step.get("haltOnError"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(DbkgConstants.KEY_STEP_CODE, stepCode);
        result.put(DbkgConstants.KEY_EXECUTOR_CODE, executorCode);
        result.put(DbkgConstants.KEY_TEMPLATE_CODE, templateCode);
        result.put(DbkgConstants.KEY_STATUS, status);
        result.put(DbkgConstants.KEY_OUTPUT, output);
        result.put(DbkgConstants.KEY_HALTED, halted);
        return result;
    }

    private Map<String, Map<String, Object>> indexStepsByCode(List<Map<String, Object>> steps) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            indexed.put(String.valueOf(step.get(DbkgConstants.KEY_STEP_CODE)), step);
        }
        return indexed;
    }

    private String resolveNextStepCode(
            String currentStepCode,
            Map<String, Object> stepResult,
            Map<String, Object> runtime,
            List<Map<String, Object>> transitions) {
        if (transitions.isEmpty()) {
            return null;
        }
        String status = support.asText(stepResult.get(DbkgConstants.KEY_STATUS));
        Map<String, Object> variables = buildTransitionVariables(stepResult, runtime);
        for (Map<String, Object> transition : transitions) {
            String fromStep = support.asText(transition.get("fromStepCode"));
            String outcome = support.asText(transition.get("outcomeCode"));
            if (!currentStepCode.equalsIgnoreCase(fromStep)) {
                continue;
            }
            if (status.equalsIgnoreCase(outcome)
                    && outcomeResolver.evaluateCondition(support.asText(transition.get("conditionExpr")), variables)) {
                String toStep = support.asText(transition.get("toStepCode"));
                return toStep.isBlank() ? null : toStep;
            }
        }
        for (Map<String, Object> transition : transitions) {
            String fromStep = support.asText(transition.get("fromStepCode"));
            if (!currentStepCode.equalsIgnoreCase(fromStep)) {
                continue;
            }
            if (!outcomeResolver.evaluateCondition(support.asText(transition.get("conditionExpr")), variables)) {
                continue;
            }
            String toStep = support.asText(transition.get("toStepCode"));
            return toStep.isBlank() ? null : toStep;
        }
        return null;
    }

    private String nextBySequence(List<Map<String, Object>> stepResults, List<Map<String, Object>> steps) {
        if (stepResults.size() >= steps.size()) {
            return null;
        }
        return String.valueOf(steps.get(stepResults.size()).get("stepCode"));
    }

    private Map<String, Object> buildTransitionVariables(Map<String, Object> stepResult, Map<String, Object> runtime) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(DbkgConstants.KEY_STATUS, support.asText(stepResult.get(DbkgConstants.KEY_STATUS)));
        variables.put(DbkgConstants.KEY_PLACEHOLDER_SKIPPED, Boolean.TRUE.equals(runtime.get(DbkgConstants.KEY_PLACEHOLDER_SKIPPED)));
        variables.put(DbkgConstants.KEY_ROW_COUNT, support.parseInt(runtime.get(DbkgConstants.KEY_LAST_ROW_COUNT), 0));
        variables.put(DbkgConstants.KEY_REQUEST_ROW_COUNT, support.parseInt(runtime.get(DbkgConstants.KEY_REQUEST_ROW_COUNT), 0));
        variables.put(DbkgConstants.KEY_DAG_ERROR, support.asText(runtime.get(DbkgConstants.KEY_DAG_ERROR)));
        variables.put(DbkgConstants.KEY_QUESTION, support.asText(runtime.get(DbkgConstants.KEY_QUESTION)));
        variables.put(DbkgConstants.KEY_EXECUTION_MODE, support.asText(runtime.get(DbkgConstants.KEY_EXECUTION_MODE)));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) runtime.getOrDefault(DbkgConstants.KEY_ARGS, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedCase = (Map<String, Object>) runtime.getOrDefault(DbkgConstants.KEY_CASE, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPlaybook = (Map<String, Object>) runtime.getOrDefault(DbkgConstants.KEY_PLAYBOOK, Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiFlows = (List<Map<String, Object>>) runtime.getOrDefault(DbkgConstants.KEY_API_FLOWS, List.of());
        variables.put("apiFlowCount", apiFlows.size());
        variables.put("caseCode", support.asText(selectedCase.get("caseCode")));
        variables.put("caseName", support.asText(selectedCase.get("caseName")));
        variables.put("playbookCode", support.asText(selectedPlaybook.get("playbookCode")));
        variables.put("playbookName", support.asText(selectedPlaybook.get("playbookName")));
        if (!apiFlows.isEmpty()) {
            Map<String, Object> firstApiFlow = apiFlows.get(0);
            variables.put("firstApiFlowCode", support.asText(firstApiFlow.get("apiCode")));
            variables.put("firstApiFlowName", support.asText(firstApiFlow.get("apiName")));
            variables.put("firstApiFlowSystemCode", support.asText(firstApiFlow.get("systemCode")));
        }
        addFlatVariables(variables, "arg_", args);
        addFlatVariables(variables, "case_", selectedCase);
        addFlatVariables(variables, "playbook_", selectedPlaybook);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) stepResult.getOrDefault(DbkgConstants.KEY_OUTPUT, Map.of());
        if (output != null) {
            Object rowCount = output.get(DbkgConstants.KEY_ROW_COUNT);
            if (rowCount != null) {
                variables.put(DbkgConstants.KEY_ROW_COUNT,
                        support.parseInt(rowCount, support.parseInt(runtime.get(DbkgConstants.KEY_LAST_ROW_COUNT), 0)));
            }
            for (Map.Entry<String, Object> entry : output.entrySet()) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }
        return variables;
    }

    private void addFlatVariables(Map<String, Object> variables, String prefix, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
                continue;
            }
            variables.put(prefix + entry.getKey(), value);
        }
    }

}
