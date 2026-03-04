package com.github.salilvnair.convengine.engine.mcp.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbkgSummaryStepExecutor implements DbkgStepExecutor {

    private final DbkgSupportService support;

    @Override
    public boolean supports(String executorCode) {
        return DbkgConstants.EXECUTOR_SUMMARY_RENDERER.equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config, Map<String, Object> runtime) {
        @SuppressWarnings("unchecked")
        Map<String, Object> playbook = (Map<String, Object>) runtime.getOrDefault(DbkgConstants.KEY_PLAYBOOK, Map.of());
        String playbookCode = support.asText(playbook.get(DbkgConstants.KEY_PLAYBOOK_CODE));
        List<String> completedSteps = new ArrayList<>(support.stepOutputs(runtime).keySet());
        String style = support.asText(config.get(DbkgConstants.KEY_SUMMARY_STYLE));
        String summary = "Playbook " + playbookCode + " executed " + completedSteps.size()
                + " step(s) using summary style " + (style.isBlank() ? "default" : style) + ".";
        if (Boolean.TRUE.equals(runtime.get(DbkgConstants.KEY_PLACEHOLDER_SKIPPED))) {
            summary += " One or more placeholder query templates were skipped because consumer transaction tables are not configured yet.";
        } else if (runtime.containsKey(DbkgConstants.KEY_LAST_ROW_COUNT)) {
            summary += " The latest query returned " + runtime.get(DbkgConstants.KEY_LAST_ROW_COUNT) + " row(s).";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DbkgConstants.KEY_SUMMARY, summary);
        out.put(DbkgConstants.KEY_COMPLETED_STEPS, completedSteps);
        out.put(DbkgConstants.KEY_PLACEHOLDER_SKIPPED, runtime.get(DbkgConstants.KEY_PLACEHOLDER_SKIPPED));
        out.put(DbkgConstants.KEY_LAST_ROW_COUNT, runtime.getOrDefault(DbkgConstants.KEY_LAST_ROW_COUNT, 0));
        return out;
    }
}
