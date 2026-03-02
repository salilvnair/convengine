package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbkgCaseResolver {

    private final DbkgSupportService support;

    public Map<String, Object> resolveCase(Map<String, Object> args, EngineSession session) {
        String question = support.extractQuestion(args, session);
        List<String> tokens = support.normalizeTokens(question);
        List<Map<String, Object>> cases = support.readEnabledRows(support.cfg().getCaseTypeTable());
        List<Map<String, Object>> signals = support.readEnabledRows(support.cfg().getCaseSignalTable());
        List<Map<String, Object>> rankedCases = support.rankSignals(cases, signals, "case_code", question, tokens);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(DbkgConstants.KEY_QUESTION, question);
        response.put(DbkgConstants.KEY_TOKENS, tokens);
        response.put(DbkgConstants.KEY_RANKED_CASES, rankedCases);
        response.put(DbkgConstants.KEY_SELECTED_CASE, rankedCases.isEmpty() ? null : rankedCases.get(0));
        response.put(DbkgConstants.KEY_NEEDS_CLARIFICATION, rankedCases.isEmpty());
        return response;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractRankedCases(Map<String, Object> args, EngineSession session) {
        return (List<Map<String, Object>>) resolveCase(args, session).getOrDefault(DbkgConstants.KEY_RANKED_CASES, List.of());
    }

    public List<Map<String, Object>> rankPlaybooks(Map<String, Object> selectedCase, String question, List<String> tokens) {
        if (selectedCase == null || selectedCase.get("caseCode") == null) {
            return List.of();
        }
        String caseCode = String.valueOf(selectedCase.get("caseCode"));
        List<Map<String, Object>> playbooks = support.readEnabledRows(support.cfg().getPlaybookTable()).stream()
                .filter(row -> caseCode.equalsIgnoreCase(support.asText(row.get("case_code"))))
                .toList();
        List<Map<String, Object>> signals = support.readEnabledRows(support.cfg().getPlaybookSignalTable());
        return support.rankSignals(playbooks, signals, "playbook_code", question, tokens);
    }
}
