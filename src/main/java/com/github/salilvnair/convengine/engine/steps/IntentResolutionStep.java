package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.intent.CompositeIntentResolver;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class IntentResolutionStep implements EngineStep {

    private final CompositeIntentResolver intentResolver;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        String previousIntent = session.getIntent();

        audit.audit("INTENT_RESOLVE_START", session.getConversationId(),
                "{\"previousIntent\":\"" + JsonUtil.escape(previousIntent) + "\"}");

        CompositeIntentResolver.IntentResolutionResult result = intentResolver.resolveWithTrace(session);

        if (result == null || result.resolvedIntent() == null) {
            audit.audit("INTENT_RESOLVE_NO_CHANGE", session.getConversationId(), "{}");
            return new StepResult.Continue();
        }

        if (!result.resolvedIntent().equals(previousIntent)) {
            session.setIntent(result.resolvedIntent());
            session.getConversation().setIntentCode(result.resolvedIntent());
            session.getConversation().setStateCode(session.getState());

            audit.audit(
                    "INTENT_RESOLVED_BY_" + result.source().name(),
                    session.getConversationId(),
                    JsonUtil.toJson(result)
            );
        }

        return new StepResult.Continue();
    }
}
