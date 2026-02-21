package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.mcp.McpPlanner;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.McpToolExecutor;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ToolOrchestrationStep.class)
public class McpToolStep implements EngineStep {

    private static final int MAX_LOOPS = 5;

    private final McpToolRegistry registry;
    private final McpPlanner planner;
    private final List<McpToolExecutor> toolExecutors;
    private final AuditService audit;
    private final RulesStep rulesStep;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StepResult execute(EngineSession session) {
        if (Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.SKIP_TOOL_EXECUTION))
                || Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.GUARDRAIL_BLOCKED))) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "SKIPPED_BY_GUARDRAIL");
            return new StepResult.Continue();
        }

        String dAct = String.valueOf(session.getInputParams().get(ConvEngineInputParamKey.DIALOGUE_ACT));
        if (dAct != null && !dAct.isBlank() && !"null".equalsIgnoreCase(dAct)) {
            if ("AFFIRM".equalsIgnoreCase(dAct) || "NEGATE".equalsIgnoreCase(dAct)
                    || "EDIT".equalsIgnoreCase(dAct) || "RESET".equalsIgnoreCase(dAct)) {
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "SKIPPED_DIALOGUE_ACT");
                return new StepResult.Continue();
            }
        }

        String userText = session.getUserText();
        if (userText != null && userText.trim().toLowerCase()
                .matches("^(hi|hello|hey|greetings|good morning|good afternoon|good evening)\\b.*")) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "SKIPPED_GREETING");
            return new StepResult.Continue();
        }

        if (session.hasPendingClarification()) {
            audit.audit(
                    ConvEngineAuditStage.MCP_SKIPPED_PENDING_CLARIFICATION,
                    session.getConversationId(),
                    mapOf(
                            "intent", session.getIntent(),
                            "state", session.getState()));
            return new StepResult.Continue();
        }

        List<CeMcpTool> tools = registry.listEnabledTools(session.getIntent(), session.getState());

        if (CollectionUtils.isEmpty(tools)) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "NO_TOOLS_FOR_SCOPE");
            audit.audit(ConvEngineAuditStage.MCP_NO_TOOLS_AVAILABLE, session.getConversationId(),
                    mapOf("intent", session.getIntent(), "state", session.getState()));
            return new StepResult.Continue();
        }

        clearMcpContext(session);
        List<McpObservation> observations = readObservationsFromContext(session);
        boolean mcpTouched = false;

        for (int i = 0; i < MAX_LOOPS; i++) {

            McpPlan plan = planner.plan(session, tools, observations);
            mcpTouched = true;
            session.putInputParam(ConvEngineInputParamKey.MCP_ACTION, plan.action());
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_CODE, plan.tool_code());
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_ARGS, plan.args() == null ? Map.of() : plan.args());

            if ("ANSWER".equalsIgnoreCase(plan.action())) {
                // store final answer in contextJson; your ResponseResolutionStep can use it via
                // derivation_hint
                writeFinalAnswerToContext(session, plan.answer());
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        plan.answer() == null ? "" : plan.answer());
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "ANSWER");
                audit.audit(
                        ConvEngineAuditStage.MCP_FINAL_ANSWER,
                        session.getConversationId(),
                        mapOf("answer", plan.answer()));
                break;
            }

            if (!"CALL_TOOL".equalsIgnoreCase(plan.action())) {
                writeFinalAnswerToContext(session, "I couldn't decide the next tool step safely.");
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        "I couldn't decide the next tool step safely.");
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "FALLBACK");
                break;
            }

            String toolCode = plan.tool_code();
            Map<String, Object> args = (plan.args() == null) ? Map.of() : plan.args();

            audit.audit(
                    ConvEngineAuditStage.MCP_TOOL_CALL,
                    session.getConversationId(),
                    mapOf("tool_code", toolCode, "args", args));

            CeMcpTool tool = registry.requireTool(toolCode, session.getIntent(), session.getState());
            String toolGroup = registry.normalizeToolGroup(tool.getToolGroup());
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_GROUP, toolGroup);

            try {
                McpToolExecutor executor = resolveExecutor(toolGroup);
                String rowsJson = executor.execute(tool, args, session);

                observations.add(new McpObservation(toolCode, rowsJson));
                writeObservationsToContext(session, observations);
                session.putInputParam(ConvEngineInputParamKey.MCP_OBSERVATIONS, observations);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "TOOL_RESULT");

                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_RESULT,
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "tool_group", toolGroup, "rows", rowsJson));

            } catch (Exception e) {
                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_ERROR,
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "tool_group", toolGroup, "error", String.valueOf(e.getMessage())));
                writeFinalAnswerToContext(session, "Tool execution failed safely. Can you narrow the request?");
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        "Tool execution failed safely. Can you narrow the request?");
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, "TOOL_ERROR");
                break;
            }
        }

        if (mcpTouched) {
            rulesStep.applyRules(session, "McpToolStep PostMcp", RulePhase.MCP_POST_LLM.name());
        }

        session.syncToConversation();
        return new StepResult.Continue();
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

    // -------------------------------------------------
    // contextJson storage: { "mcp": { "observations": [...], "finalAnswer": "..." }
    // }
    // -------------------------------------------------

    private List<McpObservation> readObservationsFromContext(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);
            JsonNode mcp = root.path("mcp");
            JsonNode obs = mcp.path("observations");
            if (!obs.isArray())
                return new ArrayList<>();

            List<McpObservation> list = new ArrayList<>();
            for (JsonNode n : obs) {
                String tool = n.path("toolCode").asText(null);
                String json = n.path("json").asText(null);
                if (tool != null && json != null)
                    list.add(new McpObservation(tool, json));
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeObservationsToContext(EngineSession session, List<McpObservation> observations) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject("mcp");

            ArrayNode arr = mapper.createArrayNode();
            for (McpObservation o : observations) {
                ObjectNode n = mapper.createObjectNode();
                n.put("toolCode", o.toolCode());
                n.put("json", o.json());
                arr.add(n);
            }
            mcp.set("observations", arr);

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeFinalAnswerToContext(EngineSession session, String answer) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject("mcp");
            mcp.put("finalAnswer", answer == null ? "" : answer);
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private ObjectNode ensureContextObject(EngineSession session) throws Exception {
        String ctx = session.getContextJson();
        if (ctx == null || ctx.isBlank())
            return mapper.createObjectNode();
        JsonNode n = mapper.readTree(ctx);
        if (n instanceof ObjectNode o)
            return o;
        return mapper.createObjectNode();
    }

    private void clearMcpContext(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);

            // Remove stale per-turn MCP state
            if (root.has("mcp") && root.get("mcp").isObject()) {
                ((ObjectNode) root.get("mcp")).remove("finalAnswer");
                ((ObjectNode) root.get("mcp")).remove("observations");
            }

            session.setContextJson(mapper.writeValueAsString(root));

            audit.audit(
                    "MCP_CONTEXT_CLEARED",
                    session.getConversationId(),
                    Map.of());
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> mapOf(Object... kvPairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }

}
