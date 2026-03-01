package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.action.PendingActionStatus;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.PendingActionConstants;
import com.github.salilvnair.convengine.engine.dialogue.InteractionPolicyDecision;
import com.github.salilvnair.convengine.engine.helper.SessionContextHelper;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePendingAction;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(InteractionPolicyStep.class)
@MustRunBefore(PendingActionStep.class)
public class ActionLifecycleStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final StaticConfigurationCacheService staticCacheService;
    private final SessionContextHelper contextHelper;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getActionLifecycle().isEnabled()) {
            return new StepResult.Continue();
        }

        ObjectNode root = contextHelper.readRoot(session);
        ObjectNode runtime = contextHelper.ensureObject(root, PendingActionConstants.CONTEXT_PENDING_ACTION_RUNTIME);
        int currentTurn = session.conversionHistory().size() + 1;
        long now = Instant.now().toEpochMilli();

        PendingActionStatus currentStatus = PendingActionStatus.from(
                runtime.path(PendingActionConstants.RUNTIME_STATUS).asText(null), null);
        if (isExpired(runtime, currentTurn, now)
                && (currentStatus == PendingActionStatus.OPEN || currentStatus == PendingActionStatus.IN_PROGRESS)) {
            runtime.put(PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.EXPIRED.name());
            runtime.put(PendingActionConstants.RUNTIME_EXPIRED_TURN, currentTurn);
            runtime.put(PendingActionConstants.RUNTIME_EXPIRED_AT_EPOCH_MS, now);
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RUNTIME_STATUS,
                    PendingActionStatus.EXPIRED.name());
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_LIFECYCLE, session.getConversationId(), mapOf(
                    ConvEnginePayloadKey.EVENT, PendingActionStatus.EXPIRED.name(),
                    PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.EXPIRED.name(),
                    ConvEnginePayloadKey.TURN, currentTurn));
        }

        String actionKey = resolveActionKey(session);
        String actionRef = resolveActionReferenceFromTable(session, actionKey);
        if (actionRef == null || actionRef.isBlank()) {
            contextHelper.writeRoot(session, root);
            return new StepResult.Continue();
        }

        boolean isNewRuntime = isRuntimeNew(runtime, actionKey, actionRef);
        if (isNewRuntime) {
            runtime.put(PendingActionConstants.RUNTIME_ACTION_KEY, actionKey == null ? "" : actionKey);
            runtime.put(PendingActionConstants.RUNTIME_ACTION_REF, actionRef);
            runtime.put(PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.OPEN.name());
            runtime.put(PendingActionConstants.RUNTIME_CREATED_TURN, currentTurn);
            runtime.put(PendingActionConstants.RUNTIME_CREATED_AT_EPOCH_MS, now);
            runtime.put(PendingActionConstants.RUNTIME_EXPIRES_TURN, flowConfig.getActionLifecycle().getTtlTurns() > 0
                    ? currentTurn + flowConfig.getActionLifecycle().getTtlTurns()
                    : -1);
            runtime.put(PendingActionConstants.RUNTIME_EXPIRES_AT_EPOCH_MS, flowConfig.getActionLifecycle().getTtlMinutes() > 0
                    ? now + (flowConfig.getActionLifecycle().getTtlMinutes() * 60_000L)
                    : -1);
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RUNTIME_STATUS,
                    PendingActionStatus.OPEN.name());
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_LIFECYCLE, session.getConversationId(), mapOf(
                    ConvEnginePayloadKey.EVENT, PendingActionStatus.OPEN.name(),
                    PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.OPEN.name(),
                    ConvEnginePayloadKey.ACTION_KEY, actionKey,
                    ConvEnginePayloadKey.ACTION_REF, actionRef));
        }

        InteractionPolicyDecision decision = parseDecision(
                session.inputParamAsString(ConvEngineInputParamKey.POLICY_DECISION));
        if (decision == InteractionPolicyDecision.EXECUTE_PENDING_ACTION) {
            runtime.put(PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.IN_PROGRESS.name());
            runtime.put(PendingActionConstants.RUNTIME_IN_PROGRESS_TURN, currentTurn);
            runtime.put(PendingActionConstants.RUNTIME_IN_PROGRESS_AT_EPOCH_MS, now);
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RUNTIME_STATUS,
                    PendingActionStatus.IN_PROGRESS.name());
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_LIFECYCLE, session.getConversationId(), mapOf(
                    ConvEnginePayloadKey.EVENT, PendingActionStatus.IN_PROGRESS.name(),
                    PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.IN_PROGRESS.name(),
                    ConvEnginePayloadKey.ACTION_KEY, actionKey,
                    ConvEnginePayloadKey.ACTION_REF, actionRef));
        } else if (decision == InteractionPolicyDecision.REJECT_PENDING_ACTION) {
            runtime.put(PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.REJECTED.name());
            runtime.put(PendingActionConstants.RUNTIME_REJECTED_TURN, currentTurn);
            runtime.put(PendingActionConstants.RUNTIME_REJECTED_AT_EPOCH_MS, now);
            session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_RUNTIME_STATUS,
                    PendingActionStatus.REJECTED.name());
            audit.audit(ConvEngineAuditStage.PENDING_ACTION_LIFECYCLE, session.getConversationId(), mapOf(
                    ConvEnginePayloadKey.EVENT, PendingActionStatus.REJECTED.name(),
                    PendingActionConstants.RUNTIME_STATUS, PendingActionStatus.REJECTED.name(),
                    ConvEnginePayloadKey.ACTION_KEY, actionKey,
                    ConvEnginePayloadKey.ACTION_REF, actionRef));
        }

        contextHelper.writeRoot(session, root);
        return new StepResult.Continue();
    }

    private boolean isRuntimeNew(ObjectNode runtime, String actionKey, String actionRef) {
        if (runtime.isEmpty()) {
            return true;
        }
        String existingActionRef = runtime.path(PendingActionConstants.RUNTIME_ACTION_REF).asText("");
        String existingActionKey = runtime.path(PendingActionConstants.RUNTIME_ACTION_KEY).asText("");
        PendingActionStatus existingStatus = PendingActionStatus.from(
                runtime.path(PendingActionConstants.RUNTIME_STATUS).asText(null), null);
        return !actionRef.equals(existingActionRef)
                || !(actionKey == null ? "" : actionKey).equals(existingActionKey)
                || existingStatus == null
                || existingStatus == PendingActionStatus.EXECUTED
                || existingStatus == PendingActionStatus.REJECTED
                || existingStatus == PendingActionStatus.EXPIRED;
    }

    private boolean isExpired(ObjectNode runtime, int currentTurn, long nowEpochMs) {
        JsonNode expiresTurn = runtime.get(PendingActionConstants.RUNTIME_EXPIRES_TURN);
        JsonNode expiresAt = runtime.get(PendingActionConstants.RUNTIME_EXPIRES_AT_EPOCH_MS);
        boolean turnExpired = expiresTurn != null && expiresTurn.canConvertToInt() && expiresTurn.asInt(-1) >= 0
                && currentTurn >= expiresTurn.asInt();
        boolean timeExpired = expiresAt != null && expiresAt.canConvertToLong() && expiresAt.asLong(-1L) >= 0
                && nowEpochMs >= expiresAt.asLong();
        return turnExpired || timeExpired;
    }

    private String resolveActionKey(EngineSession session) {
        String fromInput = session.inputParamAsString(ConvEngineInputParamKey.PENDING_ACTION_KEY);
        if (fromInput != null && !fromInput.isBlank()) {
            return fromInput.trim();
        }
        Map<String, Object> context = session.contextDict();
        Object fromContext = context.get(PendingActionConstants.CONTEXT_PENDING_ACTION_KEY);
        if (!(fromContext instanceof String)) {
            fromContext = context.get(PendingActionConstants.CONTEXT_PENDING_ACTION_KEY_CAMEL);
        }
        if (fromContext instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        Object pendingAction = context.get(PendingActionConstants.CONTEXT_PENDING_ACTION);
        if (pendingAction instanceof Map<?, ?> map) {
            Object k = map.get(PendingActionConstants.RUNTIME_ACTION_KEY);
            if (k instanceof String s && !s.isBlank()) {
                return s.trim();
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
            if (candidates.size() > 1) {
                Integer p1 = candidates.getFirst().getPriority() == null ? Integer.MAX_VALUE
                        : candidates.getFirst().getPriority();
                Integer p2 = candidates.get(1).getPriority() == null ? Integer.MAX_VALUE
                        : candidates.get(1).getPriority();
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

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }
}
