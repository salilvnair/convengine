package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.github.salilvnair.convengine.engine.session.EngineSession;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(IntentResolutionStep.class)
public class FallbackIntentStateStep implements EngineStep {

    private final AuditService audit;
    private final String fallbackIntent = ConvEngineValue.UNKNOWN;
    private final String fallbackState = ConvEngineValue.UNKNOWN;

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getIntent() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            payload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
            payload.put(ConvEnginePayloadKey.USER_TEXT, session.getUserText());
            payload.put(ConvEnginePayloadKey.FALLBACK_INTENT, fallbackIntent);
            audit.audit(ConvEngineAuditStage.INTENT_MISSING, session.getConversationId(), payload);
            session.setIntent(fallbackIntent);
        }

        if (session.getState() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            payload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
            payload.put(ConvEnginePayloadKey.USER_TEXT, session.getUserText());
            payload.put(ConvEnginePayloadKey.FALLBACK_STATE, fallbackState);
            audit.audit(ConvEngineAuditStage.STATE_MISSING, session.getConversationId(), payload);
            session.setState(fallbackState);
        }

        session.getConversation().setIntentCode(session.getIntent());
        session.getConversation().setStateCode(session.getState());
        return new StepResult.Continue();
    }
}
