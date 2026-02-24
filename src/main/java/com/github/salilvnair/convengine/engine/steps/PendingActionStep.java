package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.action.PendingActionStatus;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.dialogue.InteractionPolicyDecision;
import com.github.salilvnair.convengine.engine.helper.SessionContextHelper;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.task.CeTaskExecutor;
import com.github.salilvnair.convengine.entity.CePendingAction;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter({ AutoAdvanceStep.class, InteractionPolicyStep.class, ActionLifecycleStep.class, DisambiguationStep.class,
        GuardrailStep.class })
@MustRunBefore(RulesStep.class)
public class PendingActionStep implements EngineStep {

    private final AuditService audit;
    private final CeTaskExecutor ceTaskExecutor;
    private final StaticConfigurationCacheService staticCacheService;
    private final SessionContextHelper contextHelper;

    @Override
    public StepResult execute(EngineSession session) {
        if (Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.SKIP_PENDING_ACTION_EXECUTION))
                || Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.GUARDRAIL_BLOCKED))) {
            Map<String, Object> payload = basePayload(session, InteractionPolicyDecision.RECLASSIFY_INTENT, null);
            payload.put(ConvEnginePayloadKey.REASON, "pending action skipped by guardrail");
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_SKIPPED, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        String decisionRaw = session.inputParamAsString(ConvEngineInputParamKey.POLICY_DECISION);
        InteractionPolicyDecision decision = parseDecision(decisionRaw);
        if (decision != InteractionPolicyDecision.EXECUTE_PENDING_ACTION
                && decision != InteractionPolicyDecision.REJECT_PENDING_ACTION) {
            return new StepResult.Continue();
        }

        Map<String, Object> context = session.contextDict();
        Object pendingAction = context.get("pending_action");
        if (pendingAction == null) {
            pendingAction = context.get("pendingAction");
        }

        String actionKey = resolveActionKey(session, context, pendingAction);
        String actionRef = resolveActionReference(session, pendingAction, actionKey);
        if (actionRef == null || actionRef.isBlank()) {
            Map<String, Object> payload = basePayload(session, decision, null);
            payload.put("actionKey", actionKey);
            payload.put(ConvEnginePayloadKey.REASON, actionKey == null || actionKey.isBlank()
                    ? "pending action reference not found or ambiguous registry mapping"
                    : "pending action reference not found");
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_SKIPPED, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        if (decision == InteractionPolicyDecision.REJECT_PENDING_ACTION) {
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RESULT, "REJECTED");
            updateRuntimeStatus(session, PendingActionStatus.REJECTED);
            Map<String, Object> payload = basePayload(session, decision, actionRef);
            payload.put(ConvEnginePayloadKey.PENDING_ACTION_RESULT, "REJECTED");
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_REJECTED, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        String[] taskRef = parseTaskReference(actionRef);
        if (taskRef == null) {
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RESULT, "FAILED");
            Map<String, Object> payload = basePayload(session, decision, actionRef);
            payload.put(ConvEnginePayloadKey.PENDING_ACTION_RESULT, "FAILED");
            payload.put(ConvEnginePayloadKey.REASON, "invalid pending action reference");
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_FAILED, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        Object executionResult = ceTaskExecutor.execute(taskRef[0], taskRef[1], session);
        if (executionResult == null) {
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RESULT, "FAILED");
            Map<String, Object> payload = basePayload(session, decision, actionRef);
            payload.put(ConvEnginePayloadKey.PENDING_ACTION_RESULT, "FAILED");
            payload.put(ConvEnginePayloadKey.REASON, "task execution returned null");
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_FAILED, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RESULT, "EXECUTED");
        updateRuntimeStatus(session, PendingActionStatus.EXECUTED);
        Map<String, Object> payload = basePayload(session, decision, actionRef);
        payload.put(ConvEnginePayloadKey.PENDING_ACTION_RESULT, "EXECUTED");
        payload.put("taskBean", taskRef[0]);
        payload.put("taskMethods", taskRef[1]);
        audit.audit(ConvEngineAuditStage.PENDING_ACTION_EXECUTED, session.getConversationId(), payload);

        return new StepResult.Continue();
    }

    private InteractionPolicyDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return InteractionPolicyDecision.RECLASSIFY_INTENT;
        }
        try {
            return InteractionPolicyDecision.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return InteractionPolicyDecision.RECLASSIFY_INTENT;
        }
    }

    private Map<String, Object> basePayload(EngineSession session, InteractionPolicyDecision decision,
            String actionRef) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.POLICY_DECISION, decision.name());
        payload.put(ConvEnginePayloadKey.PENDING_ACTION_REF, actionRef);
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
        return payload;
    }

    private String resolveActionReference(EngineSession session, Object pendingAction, String actionKey) {
        String fromTable = resolveActionReferenceFromTable(session, actionKey);
        if (fromTable != null && !fromTable.isBlank()) {
            return fromTable;
        }

        if (pendingAction instanceof String s) {
            return s.trim();
        }
        if (pendingAction instanceof Map<?, ?> map) {
            String direct = firstText(map, "task", "action", "action_value", "actionValue", "set_task", "setTask");
            if (direct != null && !direct.isBlank()) {
                return direct.trim();
            }
            String bean = firstText(map, "bean", "bean_name", "beanName");
            String method = firstText(map, "method", "method_name", "methodName", "methods", "method_names");
            if (bean != null && !bean.isBlank() && method != null && !method.isBlank()) {
                return bean.trim() + ":" + method.trim();
            }
        }
        return null;
    }

    private String resolveActionReferenceFromTable(EngineSession session, String actionKey) {
        List<CePendingAction> candidates;
        if (actionKey != null && !actionKey.isBlank()) {
            candidates = staticCacheService.findEligiblePendingActionsByActionIntentAndState(
                    actionKey,
                    session.getIntent(),
                    session.getState());
        } else {
            candidates = staticCacheService.findEligiblePendingActionsByIntentAndState(
                    session.getIntent(),
                    session.getState());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (actionKey == null || actionKey.isBlank()) {
            // Auto-resolve only when registry returns a single clear candidate.
            if (candidates.size() > 1) {
                CePendingAction first = candidates.getFirst();
                CePendingAction second = candidates.get(1);
                Integer p1 = first.getPriority() == null ? Integer.MAX_VALUE : first.getPriority();
                Integer p2 = second.getPriority() == null ? Integer.MAX_VALUE : second.getPriority();
                if (p1.equals(p2)) {
                    return null;
                }
            }
        }
        CePendingAction best = candidates.getFirst();
        if (best.getBeanName() == null || best.getBeanName().isBlank()
                || best.getMethodNames() == null || best.getMethodNames().isBlank()) {
            return null;
        }
        return best.getBeanName().trim() + ":" + best.getMethodNames().trim();
    }

    private String resolveActionKey(EngineSession session, Map<String, Object> context, Object pendingAction) {
        String fromInput = session.inputParamAsString(ConvEngineInputParamKey.PENDING_ACTION_KEY);
        if (fromInput != null && !fromInput.isBlank()) {
            return fromInput.trim();
        }

        Object fromContext = context.get("pending_action_key");
        if (!(fromContext instanceof String)) {
            fromContext = context.get("pendingActionKey");
        }
        if (fromContext instanceof String s && !s.isBlank()) {
            return s.trim();
        }

        if (pendingAction instanceof Map<?, ?> map) {
            String key = firstText(map, "action_key", "actionKey", "key", "type");
            if (key != null && !key.isBlank()) {
                return key.trim();
            }
        }
        return null;
    }

    private String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private String[] parseTaskReference(String actionRef) {
        if (actionRef == null || actionRef.isBlank()) {
            return null;
        }
        if (!actionRef.contains(":")) {
            return new String[] { actionRef.trim(), actionRef.trim() };
        }
        String[] parts = actionRef.split(":", 2);
        String bean = parts[0] == null ? "" : parts[0].trim();
        String methods = parts.length > 1 && parts[1] != null ? parts[1].trim() : "";
        if (bean.isBlank() || methods.isBlank()) {
            return null;
        }
        return new String[] { bean, methods };
    }

    private void updateRuntimeStatus(EngineSession session, PendingActionStatus status) {
        try {
            ObjectNode root = contextHelper.readRoot(session);
            ObjectNode runtime = contextHelper.ensureObject(root, "pending_action_runtime");
            runtime.put("status", status.name());
            contextHelper.writeRoot(session, root);
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RUNTIME_STATUS, status.name());
        } catch (Exception ignored) {
        }
    }

}
