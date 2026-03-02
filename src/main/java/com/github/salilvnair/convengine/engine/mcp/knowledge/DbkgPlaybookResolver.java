package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbkgPlaybookResolver {

    private final DbkgSupportService support;
    private final DbkgCaseResolver caseResolver;

    public Map<String, Object> planInvestigation(Map<String, Object> args, EngineSession session) {
        String requestedPlaybookCode = support.asText(args == null ? null : args.get("playbookCode"));
        String question = support.extractQuestion(args, session);
        List<String> tokens = support.normalizeTokens(question);
        List<Map<String, Object>> rankedCases = caseResolver.extractRankedCases(args, session);
        Map<String, Object> selectedCase = rankedCases.isEmpty() ? null : rankedCases.get(0);
        List<Map<String, Object>> rankedPlaybooks = requestedPlaybookCode.isBlank()
                ? caseResolver.rankPlaybooks(selectedCase, question, tokens)
                : rankPlaybooksByCode(selectedCase, requestedPlaybookCode);
        Map<String, Object> selectedPlaybook = rankedPlaybooks.isEmpty() ? null : rankedPlaybooks.get(0);
        List<Map<String, Object>> apiFlows = support.rankTextRows(
                support.readEnabledRowsOptional(support.cfg().getApiFlowTable()),
                tokens,
                List.of("api_name", "description", "system_code", "metadata_json", "llm_hint"),
                List.of("api_code", "api_name", "system_code", "description", "metadata_json", "llm_hint"),
                "score");
        List<Map<String, Object>> steps = selectedPlaybook == null
                ? List.of()
                : support.stepsForPlaybook(String.valueOf(selectedPlaybook.get("playbookCode")));
        List<Map<String, Object>> transitions = selectedPlaybook == null
                ? List.of()
                : support.transitionsForPlaybook(String.valueOf(selectedPlaybook.get("playbookCode")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(DbkgConstants.KEY_QUESTION, question);
        response.put(DbkgConstants.KEY_SELECTED_CASE, selectedCase);
        response.put(DbkgConstants.KEY_RANKED_PLAYBOOKS, rankedPlaybooks);
        response.put(DbkgConstants.KEY_SELECTED_PLAYBOOK, selectedPlaybook);
        response.put(DbkgConstants.KEY_API_FLOWS, apiFlows);
        response.put(DbkgConstants.KEY_STEPS, steps);
        response.put(DbkgConstants.KEY_TRANSITIONS, transitions);
        response.put(DbkgConstants.KEY_CAN_EXECUTE, selectedPlaybook != null && !steps.isEmpty());
        response.put(DbkgConstants.KEY_NEEDS_CLARIFICATION, selectedPlaybook == null);
        return response;
    }

    private List<Map<String, Object>> rankPlaybooksByCode(Map<String, Object> selectedCase, String requestedPlaybookCode) {
        List<Map<String, Object>> rows = support.readEnabledRows(support.cfg().getPlaybookTable()).stream()
                .filter(row -> requestedPlaybookCode.equalsIgnoreCase(support.asText(row.get("playbook_code"))))
                .map(row -> {
                    Map<String, Object> normalized = support.normalizePlaybook(row);
                    normalized.put("score", 1.0d);
                    return normalized;
                })
                .toList();
        if (selectedCase == null) {
            return rows;
        }
        String selectedCaseCode = support.asText(selectedCase.get("caseCode"));
        List<Map<String, Object>> sameCase = rows.stream()
                .filter(row -> selectedCaseCode.equalsIgnoreCase(support.asText(row.get("caseCode"))))
                .toList();
        return sameCase.isEmpty() ? rows : sameCase;
    }
}
