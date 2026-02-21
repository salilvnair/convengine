package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.intent.CompositeIntentResolver;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class IntentResolutionStep implements EngineStep {

    private final CompositeIntentResolver intentResolver;
    private final AuditService audit;
    private final OutputSchemaRepository outputSchemaRepository;
    private final CeConfigResolver configResolver;

    private static final Set<String> RESET_COMMANDS = Set.of(
            "reset",
            "restart",
            "/reset",
            "/restart"
    );
    private static final Set<String> FORCE_RESOLVE_INPUT_PARAM_KEYS = Set.of(
            "force_intent_resolution",
            "resolve_intent",
            "switch_intent",
            "switch_flow",
            "switch_mode"
    );
    private boolean stickyIntentEnabled = true;

    @PostConstruct
    void init() {
        stickyIntentEnabled = configResolver.resolveBoolean(this, "STICKY_INTENT", true);
    }

    @Override
    public StepResult execute(EngineSession session) {

        String previousIntent = session.getIntent();

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put(ConvEnginePayloadKey.PREVIOUS_INTENT, previousIntent);
        startPayload.put(ConvEnginePayloadKey.INTENT_LOCKED, session.isIntentLocked());
        startPayload.put(ConvEnginePayloadKey.INTENT_LOCK_REASON, session.getIntentLockReason());
        audit.audit(ConvEngineAuditStage.INTENT_RESOLVE_START, session.getConversationId(), startPayload);

        if (session.isIntentLocked() || isActiveSchemaCollection(session)) {
            if (!session.isIntentLocked()) {
                session.lockIntent("SCHEMA_INCOMPLETE");
            }
            session.clearClarification();
            if (session.getConversation() != null) {
                session.getConversation().setIntentCode(session.getIntent());
                session.getConversation().setStateCode(session.getState());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            payload.put(ConvEnginePayloadKey.INTENT_LOCKED, session.isIntentLocked());
            payload.put(ConvEnginePayloadKey.INTENT_LOCK_REASON, session.getIntentLockReason());
            audit.audit(ConvEngineAuditStage.INTENT_RESOLVE_SKIPPED_SCHEMA_COLLECTION, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        if (shouldSkipResolutionForPolicy(session)) {
            if (session.getConversation() != null) {
                session.getConversation().setIntentCode(session.getIntent());
                session.getConversation().setStateCode(session.getState());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            payload.put(ConvEnginePayloadKey.DIALOGUE_ACT, session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT));
            payload.put(ConvEnginePayloadKey.POLICY_DECISION, session.inputParamAsString(ConvEngineInputParamKey.POLICY_DECISION));
            payload.put(ConvEnginePayloadKey.SKIP_INTENT_RESOLUTION, true);
            payload.put(ConvEnginePayloadKey.REASON, "policy decision retained existing intent/state");
            audit.audit(ConvEngineAuditStage.INTENT_RESOLVE_SKIPPED_POLICY, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        if (shouldSkipResolutionForStickyIntent(session)) {
            if (session.getConversation() != null) {
                session.getConversation().setIntentCode(session.getIntent());
                session.getConversation().setStateCode(session.getState());
            }
            Map<String, Object> payload = existingIntentRetainedAuditPayload(session);
            audit.audit(ConvEngineAuditStage.INTENT_RESOLVE_SKIPPED_STICKY_INTENT, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        CompositeIntentResolver.IntentResolutionResult result = intentResolver.resolveWithTrace(session);

        if (result == null || result.resolvedIntent() == null) {
            audit.audit(ConvEngineAuditStage.INTENT_RESOLVE_NO_CHANGE, session.getConversationId(), Map.of());
            return new StepResult.Continue();
        }

        if (!result.resolvedIntent().equals(previousIntent)) {
            session.setIntent(result.resolvedIntent());
        }
        session.getConversation().setIntentCode(session.getIntent());
        session.getConversation().setStateCode(session.getState());

        audit.audit(
                ConvEngineAuditStage.intentResolvedBy(result.source().name()),
                session.getConversationId(),
                result
        );

        return new StepResult.Continue();
    }

    private static @NonNull Map<String, Object> existingIntentRetainedAuditPayload(EngineSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.STICKY_INTENT_ENABLED, true);
        payload.put(ConvEnginePayloadKey.REASON, "existing intent retained");
        return payload;
    }

    private boolean isActiveSchemaCollection(EngineSession session) {
        if (session.getIntent() == null || session.getIntent().isBlank()) {
            return false;
        }
        try {
            Optional<CeOutputSchema> schema = outputSchemaRepository
                    .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                            session.getIntent(),
                            session.getState()
                    );

            if (schema.isEmpty()) {
                schema = outputSchemaRepository
                        .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                                session.getIntent(),
                                "ANY"
                        );
            }
            if (schema.isEmpty() || schema.get().getJsonSchema() == null) {
                return false;
            }
            return !JsonUtil.isSchemaComplete(schema.get().getJsonSchema(), session.getContextJson());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldSkipResolutionForStickyIntent(EngineSession session) {
        if (!stickyIntentEnabled) {
            return false;
        }
        // Sticky intent is only valid while collecting incomplete schema fields.
        // For regular turns, intent should be re-evaluated (e.g., GREETINGS -> FAQ).
        if (!isActiveSchemaCollection(session)) {
            return false;
        }
        if (!hasResolvedIntent(session.getIntent())) {
            return false;
        }
        if (!hasResolvedState(session.getState())) {
            return false;
        }
        return !shouldForceIntentResolution(session);
    }

    private boolean shouldSkipResolutionForPolicy(EngineSession session) {
        if (!truthy(session.getInputParams().get(ConvEngineInputParamKey.SKIP_INTENT_RESOLUTION))) {
            return false;
        }
        return hasResolvedIntent(session.getIntent()) && hasResolvedState(session.getState());
    }

    private boolean hasResolvedIntent(String intent) {
        return intent != null && !intent.isBlank() && !"UNKNOWN".equalsIgnoreCase(intent);
    }

    private boolean hasResolvedState(String state) {
        return state != null && !state.isBlank() && !"UNKNOWN".equalsIgnoreCase(state);
    }

    private boolean shouldForceIntentResolution(EngineSession session) {
        if (session.hasPendingClarification()) {
            return true;
        }
        if (isResetCommand(session.getUserText())) {
            return true;
        }
        if (hasForceResolveInputParam(session)) {
            return true;
        }
        return isExplicitIntentSwitch(session.getUserText());
    }

    private boolean hasForceResolveInputParam(EngineSession session) {
        Map<String, Object> params = session.getInputParams();
        if (params == null || params.isEmpty()) {
            return false;
        }
        for (String key : FORCE_RESOLVE_INPUT_PARAM_KEYS) {
            if (truthy(params.get(key))) {
                return true;
            }
        }
        return false;
    }

    private boolean isResetCommand(String userText) {
        if (userText == null) {
            return false;
        }
        String normalized = userText.trim().toLowerCase();
        return RESET_COMMANDS.contains(normalized);
    }

    private boolean isExplicitIntentSwitch(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String normalized = userText.trim().toLowerCase();
        return normalized.startsWith("switch to ")
                || normalized.contains("switch intent")
                || normalized.contains("change intent")
                || normalized.contains("change flow")
                || normalized.contains("change mode");
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase();
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
        }
        return false;
    }
}
