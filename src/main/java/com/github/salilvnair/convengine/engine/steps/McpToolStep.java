package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.CorrectionConstants;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.constants.RoutingDecisionConstants;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.McpPlanner;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.McpToolExecutor;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.core.step.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    private static final int DEFAULT_MAX_LOOPS = 5;
    private static final String STEP_NAME = "McpToolStep";
    private static final String CONFIG_KEY_TOOL_MAX_LOOPS = "MCP_TOOL_MAX_LOOPS";
    private static final String CONFIG_KEY_TOOL_CALL_DELAY_MS = "MCP_TOOL_CALL_DELAY_MS";
    private static final String CONFIG_KEY_TOOL_CALL_DELAY_AFTER_CALLS = "MCP_TOOL_CALL_DELAY_AFTER_CALLS";
    private static final String CONFIG_KEY_TOOL_CALL_DELAY_AFTER_MS = "MCP_TOOL_CALL_DELAY_AFTER_MS";
    private static final String GREETING_REGEX = "^(hi|hello|hey|greetings|good morning|good afternoon|good evening)\\b.*";
    private static final String OPERATION_TAG_POLICY_RESTRICTED_OPERATION = "POLICY_RESTRICTED_OPERATION";
    private static final String FALLBACK_POLICY_RESTRICTED = "This request is restricted by policy. Read-only operations are allowed.";
    private static final String TOOL_DB_SEMANTIC_INTERPRET = "db.semantic.interpret";
    private static final String TOOL_DB_SEMANTIC_QUERY = "db.semantic.query";
    private static final String TOOL_POSTGRES_QUERY = "postgres.query";
    private static final String CONTEXT_KEY_SEMANTIC = "semantic";
    private static final String CONTEXT_KEY_SEMANTIC_PIPELINE = "pipeline";
    private static final String CONTEXT_KEY_SEMANTIC_TOOLS = "tools";
    private static final String CONTEXT_KEY_SEMANTIC_CLARIFICATION = "clarification";
    private static final String CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY = "semanticQueryAmbiguity";

    private final McpToolRegistry registry;
    private final McpPlanner planner;
    private final List<McpToolExecutor> toolExecutors;
    private final AuditService audit;
    private final RulesStep rulesStep;
    private final ConvEngineMcpConfig mcpConfig;
    private final VerboseMessagePublisher verbosePublisher;
    private final CeConfigResolver configResolver;

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
        boolean clarificationReply = isSemanticClarificationReply(session, dialogueAct);
        if (clarificationReply) {
            // Clarification answer should continue semantic MCP flow.
            session.clearClarification();
            clearPendingClarificationFromContext(session);
            dialogueAct = DialogueAct.ANSWER;
            session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT, DialogueAct.ANSWER.name());
            markSemanticClarificationResolved(session);
        }
        if (dialogueAct != null) {
            boolean confirmAccept = RoutingDecisionConstants.PROCEED_CONFIRMED.equalsIgnoreCase(routingDecision);
            boolean skipForDialogueAct = dialogueAct == DialogueAct.NEGATE
                    || (dialogueAct == DialogueAct.EDIT && !clarificationReply)
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
                .matches(GREETING_REGEX)) {
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
        Set<String> executedToolSignatures = new HashSet<>();
        boolean mcpTouched = false;
        boolean finalAnswerDetermined = false;
        boolean toolExecutionAbrupted = false;

        int maxLoops = resolveMaxLoops();
        writeMcpExecutionFlagsToContext(session, false, false, maxLoops);
        for (int i = 0; i < maxLoops; i++) {

            McpPlan plan = planner.plan(session, tools, observations);
            mcpTouched = true;
            String toolCode = plan.tool_code();
            Map<String, Object> args = enrichSemanticPipelineArgs(toolCode, plan.args(), observations);
            session.putInputParam(ConvEngineInputParamKey.MCP_ACTION, plan.action());
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_CODE, toolCode);
            session.putInputParam(ConvEngineInputParamKey.MCP_TOOL_ARGS, args);
            session.putInputParam("mcp_operation_tag", plan.operation_tag());
            writeLifecycleToContext(session, McpConstants.STATUS_TOOL_RESULT, McpConstants.OUTCOME_IN_PROGRESS,
                    false, false, false, plan.action(), toolCode, null, args, null);

            if (McpConstants.ACTION_ANSWER.equalsIgnoreCase(plan.action())) {
                // store final answer in contextJson; your ResponseResolutionStep can use it via
                // derivation_hint
                writeFinalAnswerToContext(session, plan.answer());
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, finalAnswerDetermined, toolExecutionAbrupted, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        plan.answer() == null ? "" : plan.answer());
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_ANSWER);
                writeLifecycleToContext(session, McpConstants.STATUS_ANSWER, McpConstants.OUTCOME_ANSWERED,
                        true, false, false, plan.action(), toolCode, null, args, null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, null, false, mapOf("answer", plan.answer()));
                audit.audit(
                        ConvEngineAuditStage.MCP_FINAL_ANSWER,
                        session.getConversationId(),
                        mapOf("answer", plan.answer()));
                break;
            }

            if (!McpConstants.ACTION_CALL_TOOL.equalsIgnoreCase(plan.action())) {
                writeFinalAnswerToContext(session, McpConstants.FALLBACK_UNSAFE_NEXT_STEP);
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, finalAnswerDetermined, toolExecutionAbrupted, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER,
                        McpConstants.FALLBACK_UNSAFE_NEXT_STEP);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_FALLBACK);
                writeLifecycleToContext(session, McpConstants.STATUS_FALLBACK, McpConstants.OUTCOME_FALLBACK,
                        true, false, false, plan.action(), toolCode, null, args, null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, null, true, mapOf("answer", McpConstants.FALLBACK_UNSAFE_NEXT_STEP));
                break;
            }
            if (isPolicyRestrictedOperationTag(plan.operation_tag())) {
                writeFinalAnswerToContext(session, FALLBACK_POLICY_RESTRICTED);
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, finalAnswerDetermined, toolExecutionAbrupted, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, FALLBACK_POLICY_RESTRICTED);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_GUARDRAIL_BLOCKED);
                writeLifecycleToContext(session, McpConstants.STATUS_GUARDRAIL_BLOCKED, McpConstants.OUTCOME_BLOCKED,
                        true, true, false, plan.action(), toolCode, null, args, OPERATION_TAG_POLICY_RESTRICTED_OPERATION);
                Map<String, Object> blockedPayload = mapOf(
                        "tool_code", toolCode,
                        "args", args,
                        "operation_tag", plan.operation_tag(),
                        "error", "planner-marked restricted operation");
                verbosePublisher.publish(session, STEP_NAME, "MCP_TOOL_ERROR", null, toolCode, true, blockedPayload);
                audit.audit(ConvEngineAuditStage.MCP_TOOL_ERROR, session.getConversationId(), blockedPayload);
                break;
            }
            String toolSignature = buildToolCallSignature(toolCode, args);
            if (executedToolSignatures.contains(toolSignature)) {
                String duplicateLoopAnswer = latestObservationSummary(observations);
                if (duplicateLoopAnswer == null || duplicateLoopAnswer.isBlank()) {
                    duplicateLoopAnswer = McpConstants.FALLBACK_UNSAFE_NEXT_STEP;
                }
                writeFinalAnswerToContext(session, duplicateLoopAnswer);
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, finalAnswerDetermined, toolExecutionAbrupted, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, duplicateLoopAnswer);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_ANSWER);
                writeLifecycleToContext(session, McpConstants.STATUS_ANSWER, McpConstants.OUTCOME_ANSWERED,
                        true, false, false, McpConstants.ACTION_ANSWER, toolCode, null, args, null);
                Map<String, Object> suppressedPayload = mapOf(
                        "answer", duplicateLoopAnswer,
                        "reason", "DUPLICATE_TOOL_CALL_SUPPRESSED",
                        "tool_code", toolCode,
                        "args", args);
                verbosePublisher.publish(session, "McpToolStep",
                        McpConstants.VERBOSE_EVENT_MCP_DUPLICATE_TOOL_CALL_SUPPRESSED, null, toolCode, false,
                        suppressedPayload);
                audit.audit(
                        McpConstants.AUDIT_STAGE_MCP_DUPLICATE_TOOL_CALL_SUPPRESSED,
                        session.getConversationId(),
                        suppressedPayload);
                break;
            }
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

            String guardrailBlockReason = nextToolGuardrailBlockReason(toolCode, observations);
            if (guardrailBlockReason != null) {
                writeFinalAnswerToContext(session, McpConstants.FALLBACK_GUARDRAIL_BLOCKED);
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, finalAnswerDetermined, toolExecutionAbrupted, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, McpConstants.FALLBACK_GUARDRAIL_BLOCKED);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_GUARDRAIL_BLOCKED);
                writeLifecycleToContext(session, McpConstants.STATUS_GUARDRAIL_BLOCKED, McpConstants.OUTCOME_BLOCKED,
                        true, true, false, plan.action(), toolCode, null, args,
                        guardrailBlockReason);
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_ERROR", null, toolCode, true, mapOf("tool_code", toolCode, "error", guardrailBlockReason));
                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_ERROR,
                        session.getConversationId(),
                        mapOf(
                                "tool_code", toolCode,
                                "error", guardrailBlockReason,
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
                applyToolDelay(i + 1);
                McpToolExecutor executor = resolveExecutor(toolGroup);
                String rowsJson = executor.execute(tool, args, session);

                observations.add(new McpObservation(toolCode, rowsJson));
                executedToolSignatures.add(toolSignature);
                writeObservationsToContext(session, observations);
                writeSemanticToolObservation(session, toolCode, rowsJson);
                session.putInputParam(ConvEngineInputParamKey.MCP_OBSERVATIONS, observations);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_TOOL_RESULT);
                writeLifecycleToContext(session, McpConstants.STATUS_TOOL_RESULT, McpConstants.OUTCOME_IN_PROGRESS,
                        false, false, false, plan.action(), toolCode, toolGroup, args, null);
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_RESULT", null, toolCode, false, mapOf("tool_code", toolCode, "tool_group", toolGroup));

                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_RESULT,
                        session.getConversationId(),
                        mapOf("tool_code", toolCode, "tool_group", toolGroup, "rows", rowsJson));

                if (TOOL_DB_SEMANTIC_INTERPRET.equalsIgnoreCase(toolCode)) {
                    rulesStep.applyRules(session, "McpToolStep PostSemanticInterpret",
                            RulePhase.POST_SEMANTIC_INTERPRET.name());
                }

                String clarificationQuestion = semanticClarificationQuestionFromObservation(toolCode, rowsJson);
                if (clarificationQuestion != null && !clarificationQuestion.isBlank()) {
                    session.setPendingClarificationQuestion(clarificationQuestion);
                    session.setPendingClarificationReason("SEMANTIC_QUERY_AMBIGUITY");
                    session.addClarificationHistory();
                    writeSemanticClarificationRequired(session, toolCode, clarificationQuestion);
                    writeFinalAnswerToContext(session, clarificationQuestion);
                    finalAnswerDetermined = true;
                    writeMcpExecutionFlagsToContext(session, true, false, maxLoops);
                    session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, clarificationQuestion);
                    session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_ANSWER);
                    writeLifecycleToContext(session, McpConstants.STATUS_ANSWER, McpConstants.OUTCOME_ANSWERED,
                            true, false, false, McpConstants.ACTION_ANSWER, toolCode, toolGroup, args, null);
                    verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, toolCode, false,
                            mapOf("answer", clarificationQuestion, "reason", "SEMANTIC_CLARIFICATION_REQUIRED"));
                    audit.audit(ConvEngineAuditStage.MCP_FINAL_ANSWER, session.getConversationId(),
                            mapOf("answer", clarificationQuestion, "reason", "SEMANTIC_CLARIFICATION_REQUIRED",
                                    "tool_code", toolCode));
                    break;
                }

                String unsupportedMessage = semanticUnsupportedMessageFromObservation(toolCode, rowsJson);
                if (unsupportedMessage != null && !unsupportedMessage.isBlank()) {
                    clearPendingClarificationFromContext(session);
                    writeSemanticUnsupported(session, toolCode, unsupportedMessage);
                    writeFinalAnswerToContext(session, unsupportedMessage);
                    finalAnswerDetermined = true;
                    writeMcpExecutionFlagsToContext(session, true, false, maxLoops);
                    session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, unsupportedMessage);
                    session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_ANSWER);
                    writeLifecycleToContext(session, McpConstants.STATUS_ANSWER, McpConstants.OUTCOME_ANSWERED,
                            true, false, false, McpConstants.ACTION_ANSWER, toolCode, toolGroup, args, null);
                    verbosePublisher.publish(session, "McpToolStep", "MCP_FINAL_ANSWER", null, toolCode, false,
                            mapOf("answer", unsupportedMessage, "reason", "SEMANTIC_UNSUPPORTED"));
                    audit.audit(ConvEngineAuditStage.MCP_FINAL_ANSWER, session.getConversationId(),
                            mapOf("answer", unsupportedMessage, "reason", "SEMANTIC_UNSUPPORTED",
                                    "tool_code", toolCode));
                    break;
                }

            } catch (Exception e) {
                Map<String, Object> errorDetails = buildErrorDetails(e);
                Map<String, Object> toolErrorPayload = mapOf(
                        "tool_code", toolCode,
                        "tool_group", toolGroup,
                        "args", args,
                        "error", String.valueOf(e.getMessage()));
                toolErrorPayload.putAll(errorDetails);
                if (args != null && args.get("preflight_diagnostics") != null) {
                    toolErrorPayload.put("preflight_diagnostics", args.get("preflight_diagnostics"));
                }
                if (args != null && args.get("root_cause_message") != null) {
                    toolErrorPayload.put("root_cause_message", args.get("root_cause_message"));
                }
                audit.audit(
                        ConvEngineAuditStage.MCP_TOOL_ERROR,
                        session.getConversationId(),
                        toolErrorPayload);
                writeToolExecutionErrorToContext(session, toolErrorPayload);
                String toolErrorMessage = resolveToolErrorMessage(session, toolCode, toolErrorPayload);
                writeFinalAnswerToContext(session, toolErrorMessage);
                finalAnswerDetermined = true;
                writeMcpExecutionFlagsToContext(session, true, false, maxLoops);
                session.putInputParam(ConvEngineInputParamKey.MCP_FINAL_ANSWER, toolErrorMessage);
                session.putInputParam(ConvEngineInputParamKey.MCP_STATUS, McpConstants.STATUS_TOOL_ERROR);
                writeLifecycleToContext(session, McpConstants.STATUS_TOOL_ERROR, McpConstants.OUTCOME_ERROR,
                        true, false, true, plan.action(), toolCode, toolGroup, args,
                        String.valueOf(toolErrorPayload.getOrDefault("root_cause_message", e.getMessage())));
                verbosePublisher.publish(session, "McpToolStep", "MCP_TOOL_CALL", null, toolCode, true,
                        toolErrorPayload);
                audit.audit("MCP_TOOL_ROOT_CAUSE", session.getConversationId(), mapOf(
                        "tool_code", toolCode,
                        "tool_group", toolGroup,
                        "error_message", toolErrorPayload.get("error_message"),
                        "root_cause_message", toolErrorPayload.get("root_cause_message"),
                        "preflight_diagnostics", toolErrorPayload.get("preflight_diagnostics"),
                        "args", args
                ));
                break;
            }
        }

        if (mcpTouched && !finalAnswerDetermined) {
            toolExecutionAbrupted = true;
            writeMcpExecutionFlagsToContext(session, false, toolExecutionAbrupted, maxLoops);
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

    private int resolveMaxLoops() {
        int yamlValue = mcpConfig == null ? DEFAULT_MAX_LOOPS : mcpConfig.getToolMaxLoops();
        int resolved = configResolver.resolveInt(this, "MCP_TOOL_MAX_LOOPS", yamlValue);
        return Math.max(1, resolved);
    }

    private void applyToolDelay(int callIndex) {
        long baseDelayMs = resolveToolDelayMs();
        if (baseDelayMs > 0) {
            sleep(baseDelayMs);
        }
        DelayPolicy policy = resolveDelayPolicy();
        if (callIndex > policy.delayAfterCalls && policy.delayAfterMs > 0) {
            sleep(policy.delayAfterMs);
        }
    }

    private long resolveToolDelayMs() {
        long yamlValue = mcpConfig == null ? 0L : mcpConfig.getToolCallDelayMs();
        int resolved = configResolver.resolveInt(this, "MCP_TOOL_CALL_DELAY_MS",
                (int) Math.min(Integer.MAX_VALUE, yamlValue));
        return Math.max(0L, resolved);
    }

    private DelayPolicy resolveDelayPolicy() {
        int yamlCalls = mcpConfig == null ? 4 : mcpConfig.getToolCallDelayAfterCalls();
        long yamlDelay = mcpConfig == null ? 5000L : mcpConfig.getToolCallDelayAfterMs();
        int calls = configResolver.resolveInt(this, "MCP_TOOL_CALL_DELAY_AFTER_CALLS", yamlCalls);
        int delayMs = configResolver.resolveInt(this, "MCP_TOOL_CALL_DELAY_AFTER_MS",
                (int) Math.min(Integer.MAX_VALUE, yamlDelay));
        return new DelayPolicy(Math.max(0, calls), Math.max(0L, delayMs));
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DelayPolicy(int delayAfterCalls, long delayAfterMs) {
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
        return McpConstants.FALLBACK_TOOL_ERROR;
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
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_FINAL_ANSWER_DETERMINED);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_TOOL_EXECUTION_ABRUPTED);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_TOOL_EXECUTION_ABRUPTION_LIMIT);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_OBSERVATIONS);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(McpConstants.CONTEXT_KEY_LIFECYCLE);
                ((ObjectNode) root.get(McpConstants.CONTEXT_KEY_MCP)).remove(CONTEXT_KEY_SEMANTIC);
            }

            session.setContextJson(mapper.writeValueAsString(root));
            if (session.getConversation() != null) {
                session.getConversation().setContextJson(session.getContextJson());
            }
            clearStaleMcpInputParams(session);

            audit.audit(
                    McpConstants.AUDIT_STAGE_MCP_CONTEXT_CLEARED,
                    session.getConversationId(),
                    Map.of());
        }
        catch (Exception ignored) {
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
        return nextToolGuardrailBlockReason(nextToolCode, observations) == null;
    }

    private String nextToolGuardrailBlockReason(String nextToolCode, List<McpObservation> observations) {
        if (!isAllowedBySemanticPipelineGuard(nextToolCode, observations)) {
            return "SEMANTIC_PIPELINE_SEQUENCE_GUARD_BLOCKED";
        }
        if (!isAllowedByConfiguredNextToolGuardrail(nextToolCode, observations)) {
            return "NEXT_TOOL_GUARDRAIL_BLOCKED";
        }
        return null;
    }

    private boolean isAllowedBySemanticPipelineGuard(String nextToolCode, List<McpObservation> observations) {
        if (nextToolCode == null || nextToolCode.isBlank() || observations == null || observations.isEmpty()) {
            return true;
        }
        McpObservation last = observations.get(observations.size() - 1);
        if (last == null || last.toolCode() == null || last.toolCode().isBlank()) {
            return true;
        }
        String lastTool = normalize(last.toolCode());
        String nextTool = normalize(nextToolCode);

        if (normalize(TOOL_DB_SEMANTIC_INTERPRET).equals(lastTool)) {
            return normalize(TOOL_DB_SEMANTIC_QUERY).equals(nextTool);
        }
        if (normalize(TOOL_DB_SEMANTIC_QUERY).equals(lastTool)) {
            return normalize(TOOL_POSTGRES_QUERY).equals(nextTool)
                    || normalize(TOOL_DB_SEMANTIC_QUERY).equals(nextTool);
        }
        return true;
    }

    private boolean isPolicyRestrictedOperationTag(String operationTag) {
        if (operationTag == null || operationTag.isBlank()) {
            return false;
        }
        return OPERATION_TAG_POLICY_RESTRICTED_OPERATION.equalsIgnoreCase(operationTag.trim());
    }

    private boolean isAllowedByConfiguredNextToolGuardrail(String nextToolCode, List<McpObservation> observations) {
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
            writeSemanticLifecycle(mcp, status, outcome, lastAction, lastToolCode, errorMessage);

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeMcpExecutionFlagsToContext(
            EngineSession session,
            boolean finalAnswerDetermined,
            boolean toolExecutionAbrupted,
            int toolExecutionAbruptionLimit) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            mcp.put(McpConstants.CONTEXT_KEY_FINAL_ANSWER_DETERMINED, finalAnswerDetermined);
            mcp.put(McpConstants.CONTEXT_KEY_TOOL_EXECUTION_ABRUPTED, toolExecutionAbrupted);
            mcp.put(McpConstants.CONTEXT_KEY_TOOL_EXECUTION_ABRUPTION_LIMIT, Math.max(1, toolExecutionAbruptionLimit));
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeToolExecutionErrorToContext(EngineSession session, Map<String, Object> toolErrorPayload) {
        if (session == null || toolErrorPayload == null || toolErrorPayload.isEmpty()) {
            return;
        }
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode err = mcp.withObject(McpConstants.CONTEXT_KEY_TOOL_EXECUTION_ERROR);
            Object value = toolErrorPayload.get("tool_code");
            if (value != null) err.put("toolCode", String.valueOf(value));
            value = toolErrorPayload.get("tool_group");
            if (value != null) err.put("toolGroup", String.valueOf(value));
            value = toolErrorPayload.get("error_message");
            if (value != null) err.put("errorMessage", String.valueOf(value));
            value = toolErrorPayload.get("root_cause_message");
            if (value != null) err.put("rootCauseMessage", String.valueOf(value));
            value = toolErrorPayload.get("root_cause_class");
            if (value != null) err.put("rootCauseClass", String.valueOf(value));
            Object preflight = toolErrorPayload.get("preflight_diagnostics");
            if (preflight != null) {
                err.set("preflightDiagnostics", mapper.valueToTree(preflight));
            }
            Object args = toolErrorPayload.get("args");
            if (args != null) {
                err.set("args", mapper.valueToTree(args));
            }
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> buildErrorDetails(Exception error) {
        Map<String, Object> details = mapOf(
                "error_class", error == null ? null : error.getClass().getName(),
                "error_message", error == null ? null : error.getMessage());
        Throwable root = rootCause(error);
        details.put("root_cause_class", root == null ? null : root.getClass().getName());
        details.put("root_cause_message", root == null ? null : root.getMessage());
        return details;
    }

    private void clearStaleMcpInputParams(EngineSession session) {
        if (session == null || session.getInputParams() == null) {
            return;
        }
        List<String> staleKeys = List.of(
                ConvEngineInputParamKey.MCP_ACTION,
                ConvEngineInputParamKey.MCP_STATUS,
                ConvEngineInputParamKey.MCP_TOOL_CODE,
                ConvEngineInputParamKey.MCP_TOOL_GROUP,
                ConvEngineInputParamKey.MCP_TOOL_ARGS,
                ConvEngineInputParamKey.MCP_OBSERVATIONS,
                ConvEngineInputParamKey.MCP_FINAL_ANSWER);
        for (String key : staleKeys) {
            session.getInputParams().remove(key);
            if (session.getSafeInputParamsForOutput() != null) {
                session.getSafeInputParamsForOutput().remove(key);
            }
        }
    }

    private String buildToolCallSignature(String toolCode, Map<String, Object> args) {
        String normalizedToolCode = normalize(toolCode);
        String normalizedArgs = normalizeArgs(args);
        return normalizedToolCode + "|" + normalizedArgs;
    }

    private String normalizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            JsonNode node = mapper.valueToTree(args);
            JsonNode normalized = canonicalizeJson(node);
            return mapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return String.valueOf(args);
        }
    }

    private JsonNode canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return mapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            Map<String, JsonNode> sorted = new java.util.TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonicalizeJson(entry.getValue())));
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(canonicalizeJson(item));
            }
            return out;
        }
        return node;
    }

    private String latestObservationSummary(List<McpObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        for (int i = observations.size() - 1; i >= 0; i--) {
            McpObservation observation = observations.get(i);
            try {
                JsonNode root = mapper.readTree(observation.json());
                String summary = root.path("summary").asText(null);
                if (summary != null && !summary.isBlank()) {
                    return summary;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Map<String, Object> enrichSemanticPipelineArgs(String toolCode,
                                                           Map<String, Object> originalArgs,
                                                           List<McpObservation> observations) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (originalArgs != null) {
            args.putAll(originalArgs);
        }
        if (toolCode == null || toolCode.isBlank() || observations == null || observations.isEmpty()) {
            return args;
        }
        String normalizedTool = normalize(toolCode);

        if (normalize(TOOL_DB_SEMANTIC_QUERY).equals(normalizedTool)
                && !args.containsKey("canonicalIntent")
                && !args.containsKey("canonical_intent")) {
            JsonNode node = latestObservationNode(observations, TOOL_DB_SEMANTIC_INTERPRET);
            JsonNode canonicalIntent = node == null ? null : node.path("canonicalIntent");
            if (canonicalIntent != null && canonicalIntent.isObject()) {
                args.put("canonicalIntent", mapper.convertValue(canonicalIntent, Map.class));
            }
        }

        if (normalize(TOOL_POSTGRES_QUERY).equals(normalizedTool)
                && !args.containsKey("query")
                && !args.containsKey("sql")) {
            JsonNode node = latestObservationNode(observations, TOOL_DB_SEMANTIC_QUERY);
            JsonNode compiledSql = node == null ? null : node.path("compiledSql");
            if (compiledSql != null && compiledSql.isObject()) {
                String sql = compiledSql.path("sql").asText("");
                JsonNode params = compiledSql.path("params");
                if (sql != null && !sql.isBlank()) {
                    args.put("query", sql);
                }
                if (params != null && params.isObject()) {
                    args.put("params", mapper.convertValue(params, Map.class));
                }
            } else if (node != null && node.isObject()) {
                String sql = node.path("compiledSql").asText("");
                JsonNode params = node.path("compiledSqlParams");
                if (sql != null && !sql.isBlank()) {
                    args.put("query", sql);
                }
                if (params != null && params.isObject()) {
                    args.put("params", mapper.convertValue(params, Map.class));
                }
            }
        }

        return args;
    }

    private String semanticClarificationQuestionFromObservation(String toolCode, String observationJson) {
        if (toolCode == null || toolCode.isBlank() || observationJson == null || observationJson.isBlank()) {
            return null;
        }
        String normalized = normalize(toolCode);
        if (!normalize(TOOL_DB_SEMANTIC_INTERPRET).equals(normalized)
                && !normalize(TOOL_DB_SEMANTIC_QUERY).equals(normalized)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(observationJson);
            boolean operationSupported = !root.path("operationSupported").isMissingNode()
                    ? root.path("operationSupported").asBoolean(true)
                    : !root.path("meta").path("operationSupported").isMissingNode()
                    ? root.path("meta").path("operationSupported").asBoolean(true)
                    : true;
            boolean unsupported = root.path("unsupported").asBoolean(false)
                    || root.path("meta").path("unsupported").asBoolean(false);
            if (!operationSupported) {
                unsupported = true;
            }
            if (unsupported) {
                return null;
            }
            boolean needsClarification = root.path("needsClarification").asBoolean(false)
                    || root.path("meta").path("needsClarification").asBoolean(false);
            if (!needsClarification) {
                return null;
            }
            String question = root.path("clarificationQuestion").asText("");
            if (question == null || question.isBlank()) {
                question = root.path("meta").path("clarificationQuestion").asText("");
            }
            return question == null || question.isBlank() ? null : question;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String semanticUnsupportedMessageFromObservation(String toolCode, String observationJson) {
        if (toolCode == null || toolCode.isBlank() || observationJson == null || observationJson.isBlank()) {
            return null;
        }
        String normalized = normalize(toolCode);
        if (!normalize(TOOL_DB_SEMANTIC_INTERPRET).equals(normalized)
                && !normalize(TOOL_DB_SEMANTIC_QUERY).equals(normalized)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(observationJson);
            boolean operationSupported = !root.path("operationSupported").isMissingNode()
                    ? root.path("operationSupported").asBoolean(true)
                    : !root.path("meta").path("operationSupported").isMissingNode()
                    ? root.path("meta").path("operationSupported").asBoolean(true)
                    : true;
            boolean unsupported = root.path("unsupported").asBoolean(false)
                    || root.path("meta").path("unsupported").asBoolean(false);
            if (!operationSupported) {
                unsupported = true;
            }
            if (unsupported) {
                String message = textAt(root, "unsupportedMessage", "meta.unsupportedMessage");
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
            JsonNode ambiguities = root.path("ambiguities");
            if (!ambiguities.isArray() || ambiguities.isEmpty()) {
                ambiguities = root.path("meta").path("ambiguities");
            }
            if (!ambiguities.isArray()) {
                return null;
            }
            for (JsonNode item : ambiguities) {
                String code = item.path("code").asText("");
                if (code != null && code.toUpperCase(Locale.ROOT).contains("UNSUPPORTED")) {
                    String msg = item.path("message").asText("");
                    return msg == null || msg.isBlank() ? "This operation is not supported for current filters." : msg;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSemanticClarificationReply(EngineSession session, DialogueAct dialogueAct) {
        if (session == null || !session.hasPendingClarification()) {
            return false;
        }
        if (dialogueAct == null) {
            return false;
        }
        boolean semanticByReason = ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY
                .equalsIgnoreCase(session.getPendingClarificationReason());
        if (!semanticByReason && !isSemanticClarificationActiveInContext(session)) {
            return false;
        }
        return dialogueAct == DialogueAct.ANSWER
                || dialogueAct == DialogueAct.EDIT
                || dialogueAct == DialogueAct.AFFIRM
                || dialogueAct == DialogueAct.NEW_REQUEST;
    }

    private boolean isSemanticClarificationActiveInContext(EngineSession session) {
        try {
            JsonNode root = mapper.readTree(session.getContextJson() == null ? "{}" : session.getContextJson());
            JsonNode clarification = root.path(McpConstants.CONTEXT_KEY_MCP)
                    .path(CONTEXT_KEY_SEMANTIC)
                    .path(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
            boolean required = clarification.path("required").asBoolean(false);
            String signal = clarification.path("signal").asText("");
            return required || "CLARIFICATION_REQUIRED".equalsIgnoreCase(signal);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void markSemanticClarificationResolved(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode semantic = mcp.withObject(CONTEXT_KEY_SEMANTIC);
            ObjectNode clarification = semantic.withObject(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
            clarification.put("resolved", true);
            clarification.put("required", false);
            clarification.put("signal", "NONE");
            semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, false);
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void clearPendingClarificationFromContext(EngineSession session) {
        try {
            ObjectNode root = ensureContextObject(session);
            root.remove("pending_clarification");
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeSemanticClarificationRequired(EngineSession session, String toolCode, String question) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode semantic = mcp.withObject(CONTEXT_KEY_SEMANTIC);
            ObjectNode clarification = semantic.withObject(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
            clarification.put("required", true);
            clarification.put("resolved", false);
            clarification.put("reason", ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
            clarification.put("question", question == null ? "" : question);
            clarification.put("sourceTool", toolCode == null ? "" : toolCode);
            clarification.put("signal", "CLARIFICATION_REQUIRED");
            semantic.put("semanticClarificationRequired", true);
            semantic.put("semanticUnsupported", false);
            semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, true);
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeSemanticUnsupported(EngineSession session, String toolCode, String message) {
        try {
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode semantic = mcp.withObject(CONTEXT_KEY_SEMANTIC);
            ObjectNode clarification = semantic.withObject(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
            clarification.put("required", false);
            clarification.put("resolved", false);
            clarification.put("reason", "SEMANTIC_UNSUPPORTED");
            clarification.put("question", "");
            clarification.put("sourceTool", toolCode == null ? "" : toolCode);
            clarification.put("signal", "UNSUPPORTED");
            semantic.put("semanticClarificationRequired", false);
            semantic.put("semanticUnsupported", true);
            semantic.put("unsupported", true);
            semantic.put("unsupportedMessage", message == null ? "" : message);
            semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, false);
            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private void writeSemanticLifecycle(
            ObjectNode mcp,
            String status,
            String outcome,
            String lastAction,
            String lastToolCode,
            String errorMessage) {
        ObjectNode semantic = mcp.withObject(CONTEXT_KEY_SEMANTIC);
        ObjectNode pipeline = semantic.withObject(CONTEXT_KEY_SEMANTIC_PIPELINE);
        pipeline.put("enabled", true);
        pipeline.put("chain", "db.semantic.interpret->db.semantic.query->postgres.query");
        pipeline.put("lastAction", lastAction == null ? "" : lastAction);
        pipeline.put("lastToolCode", lastToolCode == null ? "" : lastToolCode);
        pipeline.put("lastStatus", status == null ? "" : status);
        pipeline.put("lastOutcome", outcome == null ? "" : outcome);
        pipeline.put("lastError", errorMessage == null ? "" : errorMessage);
        pipeline.put("stage", semanticStage(lastToolCode));

        if (lastToolCode != null && !lastToolCode.isBlank()) {
            ObjectNode tools = semantic.withObject(CONTEXT_KEY_SEMANTIC_TOOLS);
            ObjectNode toolNode = tools.withObject(semanticToolKey(lastToolCode));
            toolNode.put("lastSeen", true);
            toolNode.put("lastStatus", status == null ? "" : status);
            toolNode.put("lastOutcome", outcome == null ? "" : outcome);
            toolNode.put("error", errorMessage == null ? "" : errorMessage);
            if (McpConstants.STATUS_TOOL_RESULT.equalsIgnoreCase(status)) {
                toolNode.put("completed", true);
            }
        }

        if (TOOL_DB_SEMANTIC_INTERPRET.equalsIgnoreCase(lastToolCode)) {
            // Keep a stable alias usable in ce_rule/response templates.
            ObjectNode clarification = semantic.withObject(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
            if (!clarification.has("signal")) {
                clarification.put("signal", "NONE");
            }
            semantic.put("semanticClarificationRequired",
                    "CLARIFICATION_REQUIRED".equalsIgnoreCase(clarification.path("signal").asText("NONE")));
        }
    }

    private String semanticStage(String toolCode) {
        if (TOOL_DB_SEMANTIC_INTERPRET.equalsIgnoreCase(toolCode)) {
            return "INTERPRET";
        }
        if (TOOL_DB_SEMANTIC_QUERY.equalsIgnoreCase(toolCode)) {
            return "QUERY";
        }
        if (TOOL_POSTGRES_QUERY.equalsIgnoreCase(toolCode)) {
            return "POSTGRES_EXECUTE";
        }
        return "UNKNOWN";
    }

    private String semanticToolKey(String toolCode) {
        if (TOOL_DB_SEMANTIC_INTERPRET.equalsIgnoreCase(toolCode)) {
            return "interpret";
        }
        if (TOOL_DB_SEMANTIC_QUERY.equalsIgnoreCase(toolCode)) {
            return "query";
        }
        if (TOOL_POSTGRES_QUERY.equalsIgnoreCase(toolCode)) {
            return "postgres";
        }
        return "other";
    }

    private void writeSemanticToolObservation(EngineSession session, String toolCode, String rowsJson) {
        if (session == null || toolCode == null || toolCode.isBlank() || rowsJson == null || rowsJson.isBlank()) {
            return;
        }
        try {
            JsonNode rootObs = mapper.readTree(rowsJson);
            ObjectNode root = ensureContextObject(session);
            ObjectNode mcp = root.withObject(McpConstants.CONTEXT_KEY_MCP);
            ObjectNode semantic = mcp.withObject(CONTEXT_KEY_SEMANTIC);
            ObjectNode tools = semantic.withObject(CONTEXT_KEY_SEMANTIC_TOOLS);
            ObjectNode toolNode = tools.withObject(semanticToolKey(toolCode));

            toolNode.put("toolCode", toolCode);
            toolNode.put("stage", semanticStage(toolCode));
            toolNode.put("completed", true);

            double confidence = numberAt(rootObs, "confidence", "meta.confidence");
            if (confidence >= 0.0d) {
                toolNode.put("confidence", confidence);
            }
            boolean needsClarification = boolAt(rootObs, "needsClarification", "meta.needsClarification");
            toolNode.put("needsClarification", needsClarification);
            boolean operationSupported = boolAt(rootObs, "operationSupported", "meta.operationSupported");
            if (!rootObs.path("operationSupported").isBoolean()
                    && !rootObs.path("meta").path("operationSupported").isBoolean()) {
                operationSupported = !boolAt(rootObs, "unsupported", "meta.unsupported");
            }
            toolNode.put("operationSupported", operationSupported);
            boolean unsupported = boolAt(rootObs, "unsupported", "meta.unsupported") || !operationSupported;
            toolNode.put("unsupported", unsupported);
            String unsupportedMessage = textAt(rootObs, "unsupportedMessage", "meta.unsupportedMessage");
            if (unsupportedMessage != null && !unsupportedMessage.isBlank()) {
                toolNode.put("unsupportedMessage", unsupportedMessage);
            }
            String clarificationQuestion = textAt(rootObs, "clarificationQuestion", "meta.clarificationQuestion");
            if (clarificationQuestion != null && !clarificationQuestion.isBlank()) {
                toolNode.put("clarificationQuestion", clarificationQuestion);
            }
            int rowCount = intAt(rootObs, "rowCount", "meta.rowCount", "execution.rowCount");
            if (rowCount >= 0) {
                toolNode.put("rowCount", rowCount);
            }
            String sql = textAt(rootObs, "compiledSql.sql", "compiledSql", "sql");
            if (sql != null && !sql.isBlank()) {
                toolNode.put("compiledSql", sql);
            }
            int unresolved = countAt(rootObs, "unresolvedFields", "unresolved_fields");
            if (unresolved >= 0) {
                toolNode.put("unresolvedFieldCount", unresolved);
            }
            int ambiguities = countAt(rootObs, "ambiguities", "meta.ambiguities");
            if (ambiguities >= 0) {
                toolNode.put("ambiguityCount", ambiguities);
            }

            if (unsupported) {
                semantic.put("semanticClarificationRequired", false);
                semantic.put("semanticUnsupported", true);
                semantic.put("operationSupported", false);
                semantic.put("unsupported", true);
                if (unsupportedMessage != null && !unsupportedMessage.isBlank()) {
                    semantic.put("unsupportedMessage", unsupportedMessage);
                }
                semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, false);
            } else if (needsClarification) {
                ObjectNode clarification = semantic.withObject(CONTEXT_KEY_SEMANTIC_CLARIFICATION);
                clarification.put("required", true);
                clarification.put("resolved", false);
                clarification.put("reason", ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
                clarification.put("question", clarificationQuestion == null ? "" : clarificationQuestion);
                clarification.put("sourceTool", toolCode);
                clarification.put("signal", "CLARIFICATION_REQUIRED");
                semantic.put("semanticClarificationRequired", true);
                semantic.put("semanticUnsupported", false);
                semantic.put("operationSupported", true);
                semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, true);
            } else {
                semantic.put("semanticUnsupported", false);
                semantic.put("operationSupported", true);
                semantic.put(CONTEXT_KEY_SEMANTIC_QUERY_AMBIGUITY, false);
            }

            session.setContextJson(mapper.writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }

    private String textAt(JsonNode node, String... paths) {
        if (node == null || paths == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode value = node.at(toPointer(path));
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean boolAt(JsonNode node, String... paths) {
        if (node == null || paths == null) {
            return false;
        }
        for (String path : paths) {
            JsonNode value = node.at(toPointer(path));
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isBoolean()) {
                    return value.asBoolean(false);
                }
                String text = value.asText("");
                if ("true".equalsIgnoreCase(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private double numberAt(JsonNode node, String... paths) {
        if (node == null || paths == null) {
            return -1.0d;
        }
        for (String path : paths) {
            JsonNode value = node.at(toPointer(path));
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asDouble(-1.0d);
                }
                try {
                    return Double.parseDouble(value.asText());
                } catch (Exception ignored) {
                }
            }
        }
        return -1.0d;
    }

    private int intAt(JsonNode node, String... paths) {
        if (node == null || paths == null) {
            return -1;
        }
        for (String path : paths) {
            JsonNode value = node.at(toPointer(path));
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isInt() || value.isLong()) {
                    return value.asInt(-1);
                }
                try {
                    return Integer.parseInt(value.asText());
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    private int countAt(JsonNode node, String... paths) {
        if (node == null || paths == null) {
            return -1;
        }
        for (String path : paths) {
            JsonNode value = node.at(toPointer(path));
            if (value.isArray()) {
                return value.size();
            }
        }
        return -1;
    }

    private String toPointer(String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return "";
        }
        return "/" + dottedPath.trim().replace(".", "/");
    }

    private JsonNode latestObservationNode(List<McpObservation> observations, String toolCode) {
        if (observations == null || observations.isEmpty() || toolCode == null || toolCode.isBlank()) {
            return null;
        }
        String expected = normalize(toolCode);
        for (int i = observations.size() - 1; i >= 0; i--) {
            McpObservation observation = observations.get(i);
            if (observation == null || observation.toolCode() == null) {
                continue;
            }
            if (!expected.equals(normalize(observation.toolCode()))) {
                continue;
            }
            try {
                return mapper.readTree(observation.json());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        Throwable next = current == null ? null : current.getCause();
        while (next != null && next != current) {
            current = next;
            next = current.getCause();
        }
        return current;
    }

}
