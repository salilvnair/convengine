package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
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

        CeMcpTool resolvedTool = null;
        try {
            resolvedTool = request.toolCode() == null || request.toolCode().isBlank()
                    ? null
                    : registry.requireTool(request.toolCode(), session.getIntent(), session.getState());
            String group = request.toolGroup();
            if ((group == null || group.isBlank()) && resolvedTool != null) {
                group = registry.normalizeToolGroup(resolvedTool.getToolGroup());
            }
            if (group == null || group.isBlank()) {
                throw new IllegalStateException("tool_group is required when tool_code is not resolvable");
            }

            McpToolExecutor executor = resolveExecutor(group);
            String resultJson = executor.execute(resolvedTool, request.args(), session);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", McpConstants.TOOL_STATUS_SUCCESS);
            result.put("tool_code", request.toolCode());
            result.put("tool_group", group);
            result.put("result", parseJsonOrString(resultJson));
            result.put("_meta", toolMeta(resolvedTool, request, group));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, McpConstants.TOOL_STATUS_SUCCESS);
            writeToolExecutionToContext(
                    session,
                    McpConstants.TOOL_STATUS_SUCCESS,
                    McpConstants.OUTCOME_SUCCESS,
                    false,
                    request,
                    resolvedTool,
                    group,
                    result.get("result"),
                    null);
            audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_RESULT, session.getConversationId(), result);

            rulesStep.applyRules(session, "ToolOrchestrationStep PostTool", RulePhase.POST_TOOL_EXECUTION.name());
        } catch (IllegalStateException e) {
            if (e.getMessage() != null
                    && e.getMessage().contains("Missing enabled MCP tool for current intent/state")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", McpConstants.TOOL_STATUS_SCOPE_MISMATCH);
                result.put("tool_code", request.toolCode());
                result.put("tool_group", request.toolGroup());
                result.put("intent", session.getIntent());
                result.put("state", session.getState());
                result.put("_meta", toolMeta(resolvedTool, request, request.toolGroup()));
                session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
                session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, McpConstants.TOOL_STATUS_SCOPE_MISMATCH);
                writeToolExecutionToContext(
                        session,
                        McpConstants.TOOL_STATUS_SCOPE_MISMATCH,
                        McpConstants.OUTCOME_SCOPE_MISMATCH,
                        true,
                        request,
                        resolvedTool,
                        request.toolGroup(),
                        null,
                        null);
                audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_RESULT, session.getConversationId(), result);
                return new StepResult.Continue();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", McpConstants.TOOL_STATUS_ERROR);
            result.put("tool_code", request.toolCode());
            result.put("tool_group", request.toolGroup());
            result.put("error", String.valueOf(e.getMessage()));
            result.put("_meta", toolMeta(resolvedTool, request, request.toolGroup()));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, McpConstants.TOOL_STATUS_ERROR);
            writeToolExecutionToContext(
                    session,
                    McpConstants.TOOL_STATUS_ERROR,
                    McpConstants.OUTCOME_ERROR,
                    false,
                    request,
                    resolvedTool,
                    request.toolGroup(),
                    null,
                    String.valueOf(e.getMessage()));
            audit.audit(ConvEngineAuditStage.TOOL_ORCHESTRATION_ERROR, session.getConversationId(), result);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", McpConstants.TOOL_STATUS_ERROR);
            result.put("tool_code", request.toolCode());
            result.put("tool_group", request.toolGroup());
            result.put("error", String.valueOf(e.getMessage()));
            result.put("_meta", toolMeta(resolvedTool, request, request.toolGroup()));
            session.putInputParam(ConvEngineInputParamKey.TOOL_RESULT, result);
            session.putInputParam(ConvEngineInputParamKey.TOOL_STATUS, McpConstants.TOOL_STATUS_ERROR);
            writeToolExecutionToContext(
                    session,
                    McpConstants.TOOL_STATUS_ERROR,
                    McpConstants.OUTCOME_ERROR,
                    false,
                    request,
                    resolvedTool,
                    request.toolGroup(),
                    null,
                    String.valueOf(e.getMessage()));
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

    private Map<String, Object> toolMeta(CeMcpTool tool, ToolRequest request, String resolvedToolGroup) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (tool != null) {
            meta.put("tool_id", tool.getToolId());
            meta.put("tool_code", tool.getToolCode());
            meta.put("tool_group", tool.getToolGroup());
            meta.put("intent_code", tool.getIntentCode());
            meta.put("state_code", tool.getStateCode());
            meta.put("enabled", tool.isEnabled());
            meta.put("description", tool.getDescription());
            meta.put("created_at", tool.getCreatedAt());
            return meta;
        }
        meta.put("tool_id", null);
        meta.put("tool_code", request == null ? null : request.toolCode());
        meta.put("tool_group", resolvedToolGroup == null ? null : resolvedToolGroup);
        meta.put("intent_code", null);
        meta.put("state_code", null);
        meta.put("enabled", null);
        meta.put("description", null);
        meta.put("created_at", null);
        return meta;
    }

    private void writeToolExecutionToContext(
            EngineSession session,
            String status,
            String outcome,
            boolean scopeMismatch,
            ToolRequest request,
            CeMcpTool tool,
            String resolvedToolGroup,
            Object result,
            String errorMessage) {
        try {
            ObjectNode root;
            String contextJson = session.getContextJson();
            if (contextJson == null || contextJson.isBlank()) {
                root = mapper.createObjectNode();
            } else {
                JsonNode parsed = mapper.readTree(contextJson);
                root = parsed instanceof ObjectNode objectNode ? objectNode : mapper.createObjectNode();
            }

            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode execution = mcp.withObject(McpConstants.CONTEXT_KEY_TOOL_EXECUTION);
            execution.put(McpConstants.CONTEXT_KEY_PHASE, RulePhase.POST_TOOL_EXECUTION.name());
            execution.put(McpConstants.CONTEXT_KEY_FINISHED, true);
            execution.put(McpConstants.CONTEXT_KEY_STATUS, status == null ? "" : status);
            execution.put(McpConstants.CONTEXT_KEY_OUTCOME, outcome == null ? "" : outcome);
            execution.put(McpConstants.CONTEXT_KEY_ERROR, McpConstants.TOOL_STATUS_ERROR.equalsIgnoreCase(status));
            execution.put(McpConstants.CONTEXT_KEY_SCOPE_MISMATCH, scopeMismatch);
            execution.put(McpConstants.CONTEXT_KEY_TOOL_EXECUTED,
                    McpConstants.TOOL_STATUS_SUCCESS.equalsIgnoreCase(status));
            execution.put(McpConstants.CONTEXT_KEY_TOOL_CODE, request == null ? null : request.toolCode());
            execution.put(
                    McpConstants.CONTEXT_KEY_TOOL_GROUP,
                    resolvedToolGroup == null || resolvedToolGroup.isBlank()
                            ? (request == null ? null : request.toolGroup())
                            : resolvedToolGroup);

            if (errorMessage != null && !errorMessage.isBlank()) {
                execution.put(McpConstants.CONTEXT_KEY_ERROR_MESSAGE, errorMessage);
            } else {
                execution.remove(McpConstants.CONTEXT_KEY_ERROR_MESSAGE);
            }

            if (result != null) {
                execution.set(McpConstants.CONTEXT_KEY_RESULT, mapper.valueToTree(result));
            } else {
                execution.remove(McpConstants.CONTEXT_KEY_RESULT);
            }

            execution.set(McpConstants.CONTEXT_KEY_META, mapper.valueToTree(toolMeta(tool, request, resolvedToolGroup)));

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
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
