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
        return "SUMMARY_RENDERER".equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config, Map<String, Object> runtime) {
        @SuppressWarnings("unchecked")
        Map<String, Object> playbook = (Map<String, Object>) runtime.getOrDefault("playbook", Map.of());
        String playbookCode = support.asText(playbook.get("playbookCode"));
        List<String> completedSteps = new ArrayList<>(support.stepOutputs(runtime).keySet());
        String style = support.asText(config.get("summaryStyle"));
        String summary = "Playbook " + playbookCode + " executed " + completedSteps.size()
                + " step(s) using summary style " + (style.isBlank() ? "default" : style) + ".";
        if (Boolean.TRUE.equals(runtime.get("placeholderSkipped"))) {
            summary += " One or more placeholder query templates were skipped because consumer transaction tables are not configured yet.";
        } else if (runtime.containsKey("lastRowCount")) {
            summary += " The latest query returned " + runtime.get("lastRowCount") + " row(s).";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("completedSteps", completedSteps);
        out.put("placeholderSkipped", runtime.get("placeholderSkipped"));
        out.put("lastRowCount", runtime.getOrDefault("lastRowCount", 0));
        return out;
    }
}
