package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.intent.CompositeIntentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class IntentResolutionStep implements EngineStep {

    private final CompositeIntentResolver intentResolver;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        String previousIntent = session.getIntent();

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("previousIntent", previousIntent);
        audit.audit("INTENT_RESOLVE_START", session.getConversationId(), startPayload);

        CompositeIntentResolver.IntentResolutionResult result = intentResolver.resolveWithTrace(session);

        if (result == null || result.resolvedIntent() == null) {
            audit.audit("INTENT_RESOLVE_NO_CHANGE", session.getConversationId(), Map.of());
            return new StepResult.Continue();
        }

        if (!result.resolvedIntent().equals(previousIntent)) {
            session.setIntent(result.resolvedIntent());
        }
        session.getConversation().setIntentCode(session.getIntent());
        session.getConversation().setStateCode(session.getState());

        audit.audit(
                "INTENT_RESOLVED_BY_" + result.source().name(),
                session.getConversationId(),
                result
        );

        return new StepResult.Continue();
    }
}
