package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@ConditionalOnExpression("${convengine.audit.cache-inspector:false}")
@MustRunAfter(LoadOrCreateConversationStep.class)
@MustRunBefore({ ResetConversationStep.class, AuditUserInputStep.class })
public class CacheInspectAuditStep implements EngineStep {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Override
    public StepResult execute(EngineSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cache_snapshot", objectMapper.valueToTree(session.getConversation()));
        auditService.audit(ConvEngineAuditStage.CACHE_INSPECTION, session.getConversationId(), payload);
        return new StepResult.Continue();
    }
}
