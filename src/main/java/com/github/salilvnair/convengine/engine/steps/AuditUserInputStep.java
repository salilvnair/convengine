package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class AuditUserInputStep implements EngineStep {

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.TEXT, session.getUserText());
        audit.audit(ConvEngineAuditStage.USER_INPUT, session.getConversationId(), payload);
        return new StepResult.Continue();
    }
}
