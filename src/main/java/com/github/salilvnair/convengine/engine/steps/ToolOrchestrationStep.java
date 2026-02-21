package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.McpToolExecutor;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(GuardrailStep.class)
@MustRunBefore(McpToolStep.class)
public class ToolOrchestrationStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final McpToolRegistry registry;
    private final List<McpToolExecutor> toolExecutors;
    private final RulesStep rulesStep;
    private final AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getToolOrchestration().isEnabled()) {
            return new StepResult.Continue();
        }
        if (Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.SKIP_TOOL_EXECUTION))) {
            return new StepResult.Continue();
        }

        ToolRequest request = resolveRequest(session);
        if (request == null) {
            return new StepResult.Continue();
        }

        session.putInputParam(ConvEngineInputParamKey.TOOL_REQUEST, request.toMap());
        audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_REQUEST, session.getConversationId(), request.toMap());

        try {
            CeMcpTool tool = request.toolCode() == null || request.toolCode().isBlank()
                    ? null
                    : registry.requireTool(request.toolCode(), session.getIntent(), session.getState());
            String group = request.toolGroup();
            if ((group == null || group.isBlank()) && tool != null) {
                group = registry.normalizeToolGroup(tool.getToolGroup());
            }
            if (group == null || group.isBlank()) {
                throw new IllegalStateException("tool_group is required when tool_code is not resolvable");
            }

            McpToolExecutor executor = resolveExecutor(group);
            String resultJson = executor.execute(tool, request.args(), session);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("tool_code", request.toolCode());
            result.put("tool_group", group);
            result.put("result", parseJsonOrString(resultJson));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, "SUCCESS");
            audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_RESULT, session.getConversationId(), result);

            rulesStep.applyRules(session, "ToolOrchestrationStep PostTool", RulePhase.TOOL_POST_EXECUTION.name());
        } catch (IllegalStateException e) {
            if (e.getMessage() != null
                    && e.getMessage().contains("Missing enabled MCP tool for current intent/state")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "SKIPPED_SCOPE_MISMATCH");
                result.put("tool_code", request.toolCode());
                result.put("tool_group", request.toolGroup());
                result.put("intent", session.getIntent());
                result.put("state", session.getState());
                session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
                session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, "SKIPPED_SCOPE_MISMATCH");
                audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_RESULT, session.getConversationId(), result);
                return new StepResult.Continue();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ERROR");
            result.put("tool_code", request.toolCode());
            result.put("tool_group", request.toolGroup());
            result.put("error", String.valueOf(e.getMessage()));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, "ERROR");
            audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_ERROR, session.getConversationId(), result);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ERROR");
            result.put("tool_code", request.toolCode());
            result.put("tool_group", request.toolGroup());
            result.put("error", String.valueOf(e.getMessage()));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, "ERROR");
            audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_ERROR, session.getConversationId(), result);
        }
        return new StepResult.Continue();
    }

    private ToolRequest resolveRequest(EngineSession session) {
        Object raw = session.getInputParams().get(ConvEngineInputParamKey.TOOL_REQUEST);
        if (raw == null) {
            raw = session.getInputParams().get("tool_request");
        }
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            String code = text(map.get("tool_code"));
            String group = text(map.get("tool_group"));
            Map<String, Object> args = map.get("args") instanceof Map<?, ?> argsMap ? toStringObjectMap(argsMap)
                    : Map.of();
            return new ToolRequest(code, group == null ? null : registry.normalizeToolGroup(group), args);
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                JsonNode node = mapper.readTree(s);
                if (node.isObject()) {
                    String code = node.path("tool_code").asText(null);
                    String group = node.path("tool_group").asText(null);
                    Map<String, Object> args = node.has("args") && node.get("args").isObject()
                            ? mapper.convertValue(node.get("args"), Map.class)
                            : Map.of();
                    return new ToolRequest(code, group == null ? null : registry.normalizeToolGroup(group), args);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private McpToolExecutor resolveExecutor(String normalizedToolGroup) {
        for (McpToolExecutor executor : toolExecutors) {
            String group = executor.toolGroup();
            if (group != null && group.trim().toUpperCase(Locale.ROOT).equals(normalizedToolGroup)) {
                return executor;
            }
        }
        throw new IllegalStateException("No MCP tool executor found for tool group: " + normalizedToolGroup);
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Object parseJsonOrString(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record ToolRequest(String toolCode, String toolGroup, Map<String, Object> args) {
        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool_code", toolCode);
            out.put("tool_group", toolGroup);
            out.put("args", args == null ? Map.of() : args);
            return out;
        }
    }
}
