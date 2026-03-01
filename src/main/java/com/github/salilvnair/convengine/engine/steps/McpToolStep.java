package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.CorrectionConstants;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.RoutingDecisionConstants;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.McpPlanner;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.McpToolExecutor;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ToolOrchestrationStep.class)
@MustRunBefore(ResponseResolutionStep.class)
public class McpToolStep implements EngineStep {

    private static final int MAX_LOOPS = 5;

    private final McpToolRegistry registry;
    private final McpPlanner planner;
    private final List<McpToolExecutor> toolExecutors;
    private final AuditService audit;
    private final RulesStep rulesStep;
    private final ConvEngineMcpConfig mcpConfig;
    private final VerboseMessagePublisher verbosePublisher;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StepResult execute(EngineSession session) {
        rulesStep.applyRules(session, "McpToolStep PreMcp", RulePhase.PRE_AGENT_MCP.name());

        if (Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.SKIP_TOOL_EXECUTION))
                || Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.GUARDRAIL_BLOCKED))) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_SKIPPED_BY_GUARDRAIL);
            writeLifecycleToContext(session, McpConstants.STATUS_SKIPPED_BY_GUARDRAIL, McpConstants.OUTCOME_SKIPPED,
                    true, false, false, null, null, null, null, null);
            return new StepResult.Continue();
        }

        if (session.getResolvedSchema() != null && !session.isSchemaComplete()) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_SKIPPED_SCHEMA_INCOMPLETE);
            writeLifecycleToContext(session, McpConstants.STATUS_SKIPPED_SCHEMA_INCOMPLETE, McpConstants.OUTCOME_SKIPPED,
                    true, false, false, null, null, null, null, "SCHEMA_INCOMPLETE");
            audit.audit(
                    ConvEngineAuditStage.MCP_SKIPPED_SCHEMA_INCOMPLETE,
                    session.getConversationId(),
                    mapOf(
                            "intent", session.getIntent(),
                            "state", session.getState(),
                            "missing_fields", session.getMissingRequiredFields(),
                            "schema_complete", false));
            return new StepResult.Continue();
        }

        DialogueAct dialogueAct = parseDialogueAct(session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT));
        String routingDecision = session.inputParamAsString(ConvEngineInputParamKey.ROUTING_DECISION);
        if (dialogueAct != null) {
            boolean confirmAccept = RoutingDecisionConstants.PROCEED_CONFIRMED.equalsIgnoreCase(routingDecision);
            boolean skipForDialogueAct = dialogueAct == DialogueAct.NEGATE
                    || dialogueAct == DialogueAct.EDIT
                    || dialogueAct == DialogueAct.RESET
                    || (dialogueAct == DialogueAct.AFFIRM && !confirmAccept);
            if (skipForDialogueAct) {
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_SKIPPED_DIALOGUE_ACT);
                writeLifecycleToContext(session, McpConstants.STATUS_SKIPPED_DIALOGUE_ACT, McpConstants.OUTCOME_SKIPPED,
                        true, false, false, null, null, null, null, null);
                return new StepResult.Continue();
            }
        }

        String userText = session.getUserText();
        if (userText != null && userText.trim().toLowerCase()
                .matches("^(hi|hello|hey|greetings|good morning|good afternoon|good evening)\\b.*")) {
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_SKIPPED_GREETING);
            writeLifecycleToContext(session, McpConstants.STATUS_SKIPPED_GREETING, McpConstants.OUTCOME_SKIPPED,
                    true, false, false, null, null, null, null, null);
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
            session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_NO_TOOLS_FOR_SCOPE);
            writeLifecycleToContext(session, McpConstants.STATUS_NO_TOOLS_FOR_SCOPE, McpConstants.OUTCOME_NO_TOOLS,
                    true, false, false, null, null, null, null, null);
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
            writeLifecycleToContext(session, McpConstants.STATUS_TOOL_RESULT, McpConstants.OUTCOME_IN_PROGRESS,
                    false, false, false, plan.action(), plan.tool_code(), null,
                    plan.args() == null ? Map.of() : plan.args(), null);

            if (McpConstants.ACTION_ANSWER.equalsIgnoreCase(plan.action())) {
                // store final answer in contextJson; your ResponseResolutionStep can use it via
                // derivation_hint
                writeFinalAnswerToContext(session, plan.answer());
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        plan.answer() == null ? "" : plan.answer());
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_ANSWER);
                writeLifecycleToContext(session, McpConstants.STATUS_ANSWER, McpConstants.OUTCOME_ANSWERED,
                        true, false, false, plan.action(), plan.tool_code(), null,
                        plan.args() == null ? Map.of() : plan.args(), null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, null, false, mapOf("answer", plan.answer()));
                audit.audit(
                        ConvEngineAuditStage.MCP_FINAL_ANSWER,
                        session.getConversationId(),
                        mapOf("answer", plan.answer()));
                break;
            }

            if (!McpConstants.ACTION_CALL_TOOL.equalsIgnoreCase(plan.action())) {
                writeFinalAnswerToContext(session, McpConstants.FALLBACK_UNSAFE_NEXT_STEP);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        McpConstants.FALLBACK_UNSAFE_NEXT_STEP);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_FALLBACK);
                writeLifecycleToContext(session, McpConstants.STATUS_FALLBACK, McpConstants.OUTCOME_FALLBACK,
                        true, false, false, plan.action(), plan.tool_code(), null,
                        plan.args() == null ? Map.of() : plan.args(), null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, null, true, mapOf("answer", McpConstants.FALLBACK_UNSAFE_NEXT_STEP));
                break;
            }

            String toolCode = plan.tool_code();
            Map<String, Object> args = (plan.args() == null) ? Map.of() : plan.args();
            Map<String, Object> toolCallPayload = mapOf(
                    "tool_code", toolCode,
                    "args", args,
                    "action", plan.action(),
                    "intent", session.getIntent(),
                    "state", session.getState(),
                    ConvEnginePayloadKey.ROUTING_DECISION, session.inputParamAsString(ConvEngineInputParamKey.ROUTING_DECISION),
                    "observation_count", observations.size(),
                    "current_observation_tool",
                    observations.isEmpty() ? McpConstants.FLOW_START : observations.get(observations.size() - 1).toolCode());
            verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_CALL", null, toolCode, false, toolCallPayload);

            if (!isAllowedByNextToolGuardrail(toolCode, observations)) {
                writeFinalAnswerToContext(session, McpConstants.FALLBACK_GUARDRAIL_BLOCKED);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, McpConstants.FALLBACK_GUARDRAIL_BLOCKED);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_GUARDRAIL_BLOCKED);
                writeLifecycleToContext(session, McpConstants.STATUS_GUARDRAIL_BLOCKED, McpConstants.OUTCOME_BLOCKED,
                        true, true, false, plan.action(), toolCode, null, args,
                        "NEXT_TOOL_GUARDRAIL_BLOCKED");
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_ERROR", null, toolCode, true, mapOf("tool_code", toolCode, "error", "NEXT_TOOL_GUARDRAIL_BLOCKED"));
                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_ERROR,
                        session.getConversationId(),
                        mapOf(
                                "tool_code", toolCode,
                                "error", "NEXT_TOOL_GUARDRAIL_BLOCKED",
                                "current_observation_tool",
                                observations.isEmpty() ? McpConstants.FLOW_START : observations.get(observations.size() - 1).toolCode()));
                break;
            }

            audit.audit(
                    ConvEngineAuditStage.MCP_TOOL_CALL,
                    session.getConversationId(),
                    toolCallPayload);

            CeMcpTool tool = registry.requireTool(toolCode, session.getIntent(), session.getState());
            String toolGroup = registry.normalizeToolGroup(tool.getToolGroup());
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_GROUP, toolGroup);

            try {
                McpToolExecutor executor = resolveExecutor(toolGroup);
                String rowsJson = executor.execute(tool, args, session);

                observations.add(new McpObservation(toolCode, rowsJson));
                writeObservationsToContext(session, observations);
                session.putInputParam(ConvEngineInputParamKey.MCP_OBSERVATIONS, observations);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_TOOL_RESULT);
                writeLifecycleToContext(session, McpConstants.STATUS_TOOL_RESULT, McpConstants.OUTCOME_IN_PROGRESS,
                        false, false, false, plan.action(), toolCode, toolGroup, args, null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_RESULT", null, toolCode, false, mapOf("tool_code", toolCode, "tool_group", toolGroup));

                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_RESULT,
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "tool_group", toolGroup, "rows", rowsJson));

            } catch (Exception e) {
                Map<String, Object> toolErrorPayload = mapOf(
                        "tool_code", toolCode,
                        "tool_group", toolGroup,
                        "error", String.valueOf(e.getMessage()));
                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_ERROR,
                        session.getConversationId(),
                        toolErrorPayload);
                String toolErrorMessage = resolveToolErrorMessage(session, toolCode, toolErrorPayload);
                writeFinalAnswerToContext(session, toolErrorMessage);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        toolErrorMessage);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_TOOL_ERROR);
                writeLifecycleToContext(session, McpConstants.STATUS_TOOL_ERROR, McpConstants.OUTCOME_ERROR,
                        true, false, true, plan.action(), toolCode, toolGroup, args,
                        String.valueOf(e.getMessage()));
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_CALL", null, toolCode, true,
                        toolErrorPayload);
                break;
            }
        }

        if (mcpTouched) {
            rulesStep.applyRules(session, "McpToolStep", RulePhase.POST_AGENT_MCP.name());
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

    private DialogueAct parseDialogueAct(String raw) {
        if (raw == null || raw.isBlank() || McpConstants.NULL_LITERAL.equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return DialogueAct.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveToolErrorMessage(EngineSession session, String toolCode, Map<String, Object> metadata) {
        return verbosePublisher
                .resolve(session, "McpToolStep", "MCP_TOOL_CALL", null, toolCode, true, metadata)
                .map(VerboseStreamPayload::getText)
                .filter(text -> text != null && !text.isBlank())
                .orElse(McpConstants.FALLBACK_TOOL_ERROR);
    }

    // -------------------------------------------------
    // contextJson storage: { "mcp": { "observations": [...], "finalAnswer": "..." }
    // }
    // -------------------------------------------------

    private List<McpObservation> readObservationsFromContext(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);
            JsonNode mcp = root.path(McpConstants.CONTEXT_KEY_MCP);
            JsonNode obs = mcp.path(McpConstants.CONTEXT_KEY_OBSERVATIONS);
            if (!obs.isArray())
                return new ArrayList<>();

            List<McpObservation> list = new ArrayList<>();
            for (JsonNode n : obs) {
                String tool = n.path(McpConstants.CONTEXT_OBSERVATION_TOOL_CODE).asText(null);
                String json = n.path(McpConstants.CONTEXT_OBSERVATION_JSON).asText(null);
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
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);

            ArrayNode arr = mapper.createArrayNode();
            for (McpObservation o : observations) {
                ObjectNode n = mapper.createObjectNode();
                n.put(McpConstants.CONTEXT_OBSERVATION_TOOL_CODE, o.toolCode());
                n.put(McpConstants.CONTEXT_OBSERVATION_JSON, o.json());
                arr.add(n);
            }
            mcp.set(McpConstants.CONTEXT_KEY_OBSERVATIONS, arr);

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeFinalAnswerToContext(EngineSession session, String answer) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            mcp.put(McpConstants.CONTEXT_KEY_FINAL_ANSWER, answer == null ? "" : answer);
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
            if (root.has(McpConstants.CONTEXT_KEY_MCP) && root.get(McpConstants.CONTEXT_KEY_MCP).isObject()) {
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_FINAL_ANSWER);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_OBSERVATIONS);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_LIFECYCLE);
            }

            session.setContextJson(mapper.writeValueAsString(root));

            audit.audit(
                    McpConstants.AUDIT_STAGE_MCP_CONTEXT_CLEARED,
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

    private boolean isAllowedByNextToolGuardrail(String nextToolCode, List<McpObservation> observations) {
        ConvEngineMcpConfig.Guardrail guardrail =
                mcpConfig == null || mcpConfig.getGuardrail() == null
                        ? new ConvEngineMcpConfig.Guardrail()
                        : mcpConfig.getGuardrail();

        if (!guardrail.isEnabled()) {
            return true;
        }
        if (nextToolCode == null || nextToolCode.isBlank()) {
            return false;
        }

        String currentTool = observations == null || observations.isEmpty()
                ? McpConstants.FLOW_START
                : observations.get(observations.size() - 1).toolCode();
        String currentKey = normalize(currentTool);
        String nextKey = normalize(nextToolCode);

        Map<String, List<String>> policy = guardrail.getAllowedNextByCurrentTool();
        if (policy == null || policy.isEmpty()) {
            return !guardrail.isFailClosed();
        }

        List<String> allowed = policy.get(currentTool);
        if (allowed == null) {
            allowed = policy.get(currentKey);
        }
        if (allowed == null || allowed.isEmpty()) {
            return !guardrail.isFailClosed();
        }

        Set<String> allowedSet = new HashSet<>();
        for (String candidate : allowed) {
            if (candidate != null && !candidate.isBlank()) {
                allowedSet.add(normalize(candidate));
            }
        }
        return allowedSet.contains(nextKey);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void writeLifecycleToContext(
            EngineSession session,
            String status,
            String outcome,
            boolean finished,
            boolean blocked,
            boolean error,
            String lastAction,
            String lastToolCode,
            String lastToolGroup,
            Map<String, Object> lastToolArgs,
            String errorMessage) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode lifecycle = mcp.withObject(McpConstants.CONTEXT_KEY_LIFECYCLE);

            lifecycle.put(McpConstants.CONTEXT_KEY_PHASE, RulePhase.POST_AGENT_MCP.name());
            lifecycle.put(McpConstants.CONTEXT_KEY_STATUS, status == null ? "" : status);
            lifecycle.put(McpConstants.CONTEXT_KEY_OUTCOME, outcome == null ? "" : outcome);
            lifecycle.put(McpConstants.CONTEXT_KEY_FINISHED, finished);
            lifecycle.put(McpConstants.CONTEXT_KEY_BLOCKED, blocked);
            lifecycle.put(McpConstants.CONTEXT_KEY_ERROR, error);
            lifecycle.put(McpConstants.CONTEXT_KEY_TOOL_EXECUTED, McpConstants.STATUS_TOOL_RESULT.equalsIgnoreCase(status));

            if (lastAction != null) {
                lifecycle.put(McpConstants.CONTEXT_KEY_LAST_ACTION, lastAction);
            } else {
                lifecycle.remove(McpConstants.CONTEXT_KEY_LAST_ACTION);
            }
            if (lastToolCode != null) {
                lifecycle.put(McpConstants.CONTEXT_KEY_LAST_TOOL_CODE, lastToolCode);
            } else {
                lifecycle.remove(McpConstants.CONTEXT_KEY_LAST_TOOL_CODE);
            }
            if (lastToolGroup != null) {
                lifecycle.put(McpConstants.CONTEXT_KEY_LAST_TOOL_GROUP, lastToolGroup);
            } else {
                lifecycle.remove(McpConstants.CONTEXT_KEY_LAST_TOOL_GROUP);
            }
            if (lastToolArgs != null && !lastToolArgs.isEmpty()) {
                lifecycle.set(McpConstants.CONTEXT_KEY_LAST_TOOL_ARGS, mapper.valueToTree(lastToolArgs));
            } else {
                lifecycle.remove(McpConstants.CONTEXT_KEY_LAST_TOOL_ARGS);
            }
            if (errorMessage != null && !errorMessage.isBlank()) {
                lifecycle.put(McpConstants.CONTEXT_KEY_ERROR_MESSAGE, errorMessage);
            } else {
                lifecycle.remove(McpConstants.CONTEXT_KEY_ERROR_MESSAGE);
            }

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

}
