package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.dialogue.InteractionPolicyDecision;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(DialogueActStep.class)
@MustRunBefore(IntentResolutionStep.class)
public class InteractionPolicyStep implements EngineStep {

    private final AuditService audit;
    private final StaticConfigurationCacheService staticCacheService;
    private final ConvEngineFlowConfig flowConfig;

    @Override
    public StepResult execute(EngineSession session) {
        String dialogueActRaw = session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT);
        DialogueAct dialogueAct = parseDialogueAct(dialogueActRaw);
        Map<String, Object> context = session.contextDict();
        Map<String, Object> inputParams = session.getInputParams();

        boolean hasPendingAction = hasValue(context.get("pending_action"))
                || hasValue(context.get("pendingAction"))
                || hasValue(inputParams.get("pending_action"))
                || hasValue(inputParams.get("pendingAction"))
                || hasValue(inputParams.get(ConvEngineInputParamKey.PENDING_ACTION_KEY))
                || hasValue(inputParams.get("pending_action_task"))
                || hasPendingActionFromRegistry(session);
        boolean hasPendingSlot = hasValue(context.get("pending_slot"))
                || hasValue(context.get("pendingSlot"));
        boolean hasResolvedIntent = session.getIntent() != null
                && !session.getIntent().isBlank()
                && !"UNKNOWN".equalsIgnoreCase(session.getIntent());
        boolean hasResolvedState = session.getState() != null
                && !session.getState().isBlank()
                && !"UNKNOWN".equalsIgnoreCase(session.getState());
        boolean requireResolvedIntentAndState = flowConfig.getInteractionPolicy().isRequireResolvedIntentAndState();
        boolean hasResolvedContext = !requireResolvedIntentAndState || (hasResolvedIntent && hasResolvedState);

        InteractionPolicyDecision decision = InteractionPolicyDecision.RECLASSIFY_INTENT;
        boolean skipIntentResolution = false;

        if (hasResolvedContext) {
            InteractionPolicyDecision matrixDecision = resolveFromMatrix(hasPendingAction, hasPendingSlot, dialogueAct);
            if (matrixDecision != null) {
                decision = matrixDecision;
                skipIntentResolution = true;
            } else if (flowConfig.getInteractionPolicy().isExecutePendingOnAffirm()
                    && hasPendingAction
                    && dialogueAct == DialogueAct.AFFIRM) {
                decision = InteractionPolicyDecision.EXECUTE_PENDING_ACTION;
                skipIntentResolution = true;
            } else if (flowConfig.getInteractionPolicy().isRejectPendingOnNegate()
                    && hasPendingAction
                    && dialogueAct == DialogueAct.NEGATE) {
                decision = InteractionPolicyDecision.REJECT_PENDING_ACTION;
                skipIntentResolution = true;
            } else if (flowConfig.getInteractionPolicy().isFillPendingSlotOnNonNewRequest()
                    && hasPendingSlot
                    && dialogueAct != DialogueAct.NEW_REQUEST
                    && dialogueAct != DialogueAct.GREETING) {
                decision = InteractionPolicyDecision.FILL_PENDING_SLOT;
                skipIntentResolution = true;
            }
        }

        session.putInputParam(ConvEngineInputParamKey.POLICY_DECISION, decision.name());
        session.putInputParam(ConvEngineInputParamKey.SKIP_INTENT_RESOLUTION, skipIntentResolution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT, dialogueAct.name());
        payload.put(ConvEnginePayloadKey.POLICY_DECISION, decision.name());
        payload.put(ConvEnginePayloadKey.SKIP_INTENT_RESOLUTION, skipIntentResolution);
        payload.put("hasPendingAction", hasPendingAction);
        payload.put("hasPendingSlot", hasPendingSlot);
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.INTERACTION_POLICY_DECIDED, session.getConversationId(), payload);

        return new StepResult.Continue();
    }

    private DialogueAct parseDialogueAct(String raw) {
        if (raw == null || raw.isBlank()) {
            return DialogueAct.NEW_REQUEST;
        }
        try {
            return DialogueAct.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return DialogueAct.NEW_REQUEST;
        }
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        return true;
    }

    private boolean hasPendingActionFromRegistry(EngineSession session) {
        if (session.getIntent() == null || session.getIntent().isBlank()
                || session.getState() == null || session.getState().isBlank()
                || "UNKNOWN".equalsIgnoreCase(session.getIntent())
                || "UNKNOWN".equalsIgnoreCase(session.getState())) {
            return false;
        }
        try {
            List<?> rows = staticCacheService.findEligiblePendingActionsByIntentAndState(
                    session.getIntent(),
                    session.getState());
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private InteractionPolicyDecision resolveFromMatrix(boolean hasPendingAction, boolean hasPendingSlot,
            DialogueAct dialogueAct) {
        String subject = hasPendingAction ? "PENDING_ACTION" : (hasPendingSlot ? "PENDING_SLOT" : "NONE");
        String key = subject + ":" + dialogueAct.name();
        String raw = null;
        Map<String, String> matrix = flowConfig.getInteractionPolicy().getMatrix();
        if (matrix != null) {
            raw = matrix.get(key);
            if (raw == null) {
                raw = matrix.get(key.toUpperCase(Locale.ROOT));
            }
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InteractionPolicyDecision.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }
}
