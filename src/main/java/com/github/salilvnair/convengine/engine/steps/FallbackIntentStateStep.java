package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
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
    private final String fallbackIntent = "UNKNOWN";
    private final String fallbackState = "UNKNOWN";

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getIntent() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("context", session.contextDict());
            payload.put("userText", session.getUserText());
            payload.put("fallbackIntent", fallbackIntent);
            audit.audit("INTENT_MISSING", session.getConversationId(), payload);
            session.setIntent(fallbackIntent);
        }

        if (session.getState() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("context", session.contextDict());
            payload.put("userText", session.getUserText());
            payload.put("fallbackState", fallbackState);
            audit.audit("STATE_MISSING", session.getConversationId(), payload);
            session.setState(fallbackState);
        }

        session.getConversation().setIntentCode(session.getIntent());
        session.getConversation().setStateCode(session.getState());
        return new StepResult.Continue();
    }
}
