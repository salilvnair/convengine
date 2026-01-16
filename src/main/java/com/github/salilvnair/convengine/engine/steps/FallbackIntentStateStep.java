package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.github.salilvnair.convengine.engine.session.EngineSession;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class FallbackIntentStateStep implements EngineStep {

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getIntent() == null) {
            session.setIntent("UNKNOWN");
            session.getConversation().setIntentCode("UNKNOWN");
            audit.audit("INTENT_FALLBACK", session.getConversationId(), "{\"intent\":\"UNKNOWN\"}");
        }

        if (session.getState() == null) {
            session.setState("IDLE");
            session.getConversation().setStateCode("IDLE");
            audit.audit("STATE_FALLBACK", session.getConversationId(), "{\"state\":\"IDLE\"}");
        }

        session.syncFromConversation();
        return new StepResult.Continue();
    }
}
