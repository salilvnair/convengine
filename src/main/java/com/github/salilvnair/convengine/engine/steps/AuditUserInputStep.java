package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class AuditUserInputStep implements EngineStep {

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        audit.audit("USER_INPUT", session.getConversationId(),
                "{\"text\":\"" + JsonUtil.escape(session.getUserText()) + "\"}");
        return new StepResult.Continue();
    }
}
