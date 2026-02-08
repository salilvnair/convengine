package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.mcp.McpDbExecutor;
import com.github.salilvnair.convengine.engine.mcp.McpPlanner;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class McpToolStep implements EngineStep {

    private static final int MAX_LOOPS = 5;

    private final McpToolRegistry registry;
    private final McpPlanner planner;
    private final McpDbExecutor dbExecutor;
    private final AuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StepResult execute(EngineSession session) {
        if (session.hasPendingClarification()) {
            audit.audit(
                    "MCP_SKIPPED_PENDING_CLARIFICATION",
                    session.getConversationId(),
                    mapOf(
                            "intent", session.getIntent(),
                            "state", session.getState()
                    )
            );
            return new StepResult.Continue();
        }

        List<CeMcpTool> tools = registry.listEnabledTools();

        if (CollectionUtils.isEmpty(tools)) {
            audit.audit("MCP_NO_TOOLS_AVAILABLE", session.getConversationId(), Map.of());
            return new StepResult.Continue();
        }

        clearMcpContext(session);
        List<McpObservation> observations = readObservationsFromContext(session);

        for (int i = 0; i < MAX_LOOPS; i++) {

            McpPlan plan = planner.plan(session, tools, observations);

            if ("ANSWER".equalsIgnoreCase(plan.action())) {
                // store final answer in contextJson; your ResponseResolutionStep can use it via derivation_hint
                writeFinalAnswerToContext(session, plan.answer());
                audit.audit(
                        "MCP_FINAL_ANSWER",
                        session.getConversationId(),
                        mapOf("answer", plan.answer())
                );
                break;
            }

            if (!"CALL_TOOL".equalsIgnoreCase(plan.action())) {
                writeFinalAnswerToContext(session, "I couldn't decide the next tool step safely.");
                break;
            }

            String toolCode = plan.tool_code();
            Map<String, Object> args = (plan.args() == null) ? Map.of() : plan.args();

            audit.audit(
                    "MCP_TOOL_CALL",
                    session.getConversationId(),
                    mapOf("tool_code", toolCode, "args", args)
            );

            // Only DB tools in this version
            CeMcpDbTool dbTool = registry.requireDbTool(toolCode);

            try {
                String rowsJson = dbExecutor.execute(dbTool, args);

                observations.add(new McpObservation(toolCode, rowsJson));
                writeObservationsToContext(session, observations);

                audit.audit(
                        "MCP_TOOL_RESULT",
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "rows", rowsJson)
                );

            } catch (Exception e) {
                audit.audit(
                        "MCP_TOOL_ERROR",
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "error", String.valueOf(e.getMessage()))
                );
                writeFinalAnswerToContext(session, "Tool execution failed safely. Can you narrow the request?");
                break;
            }
        }

        session.syncToConversation();
        return new StepResult.Continue();
    }

    // -------------------------------------------------
    // contextJson storage: { "mcp": { "observations": [...], "finalAnswer": "..." } }
    // -------------------------------------------------

    private List<McpObservation> readObservationsFromContext(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);
            JsonNode mcp = root.path("mcp");
            JsonNode obs = mcp.path("observations");
            if (!obs.isArray()) return new ArrayList<>();

            List<McpObservation> list = new ArrayList<>();
            for (JsonNode n : obs) {
                String tool = n.path("toolCode").asText(null);
                String json = n.path("json").asText(null);
                if (tool != null && json != null) list.add(new McpObservation(tool, json));
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
        } catch (Exception ignored) {}
    }

    private void writeFinalAnswerToContext(EngineSession session, String answer) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject("mcp");
            mcp.put("finalAnswer", answer == null ? "" : answer);
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {}
    }

    private ObjectNode ensureContextObject(EngineSession session) throws Exception {
        String ctx = session.getContextJson();
        if (ctx == null || ctx.isBlank()) return mapper.createObjectNode();
        JsonNode n = mapper.readTree(ctx);
        if (n instanceof ObjectNode o) return o;
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
                    Map.of()
            );
        } catch (Exception ignored) {}
    }

    private Map<String, Object> mapOf(Object... kvPairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }

}
