package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbKnowledgeGraphRuntimeService {

    private final DbkgCaseResolver caseResolver;
    private final DbkgKnowledgeLookupService knowledgeLookupService;
    private final DbkgPlaybookResolver playbookResolver;
    private final DbkgPlaybookEngine playbookEngine;
    private final DbkgPlaybookValidator playbookValidator;

    public Map<String, Object> resolveCase(Map<String, Object> args, EngineSession session) {
        return caseResolver.resolveCase(args, session);
    }

    public Map<String, Object> lookupKnowledge(Map<String, Object> args, EngineSession session) {
        return knowledgeLookupService.lookupKnowledge(args, session);
    }

    public Map<String, Object> planInvestigation(Map<String, Object> args, EngineSession session) {
        return playbookResolver.planInvestigation(args, session);
    }

    public Map<String, Object> executeInvestigation(Map<String, Object> args, EngineSession session) {
        return playbookEngine.executeInvestigation(args, session);
    }

    public Map<String, Object> validatePlaybook(Map<String, Object> args, EngineSession session) {
        Map<String, Object> plan = playbookResolver.planInvestigation(args, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPlaybook = (Map<String, Object>) plan.get(DbkgConstants.KEY_SELECTED_PLAYBOOK);
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedCase = (Map<String, Object>) plan.get(DbkgConstants.KEY_SELECTED_CASE);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> steps = (java.util.List<Map<String, Object>>) plan.getOrDefault(DbkgConstants.KEY_STEPS, java.util.List.of());
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> transitions = (java.util.List<Map<String, Object>>) plan.getOrDefault(DbkgConstants.KEY_TRANSITIONS, java.util.List.of());

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put(DbkgConstants.KEY_QUESTION, plan.get(DbkgConstants.KEY_QUESTION));
        response.put(DbkgConstants.KEY_SELECTED_CASE, selectedCase);
        response.put(DbkgConstants.KEY_SELECTED_PLAYBOOK, selectedPlaybook);
        response.put(DbkgConstants.KEY_RANKED_PLAYBOOKS, plan.get(DbkgConstants.KEY_RANKED_PLAYBOOKS));
        response.put(DbkgConstants.KEY_STEP_COUNT, steps.size());
        response.put(DbkgConstants.KEY_TRANSITION_COUNT, transitions.size());

        if (selectedPlaybook == null) {
            response.put(DbkgConstants.KEY_VALID, false);
            response.put(DbkgConstants.KEY_GRAPH_ERROR, DbkgConstants.MESSAGE_NO_PLAYBOOK_RESOLVED);
            response.put(DbkgConstants.KEY_SUMMARY, DbkgConstants.MESSAGE_NO_PLAYBOOK_SELECTED);
            return response;
        }

        String graphError = playbookValidator.validateGraph(steps, transitions);
        response.put(DbkgConstants.KEY_VALID, graphError == null);
        response.put(DbkgConstants.KEY_GRAPH_ERROR, graphError);
        response.put(DbkgConstants.KEY_START_STEP_CODE, graphError == null ? playbookValidator.resolveStartStepCode(steps, transitions) : null);
        response.put(DbkgConstants.KEY_CAN_EXECUTE, graphError == null && !steps.isEmpty());
        response.put(DbkgConstants.KEY_SUMMARY, graphError == null
                ? DbkgConstants.MESSAGE_PLAYBOOK_VALID
                : DbkgConstants.MESSAGE_PLAYBOOK_INVALID_PREFIX + graphError);
        return response;
    }
}
