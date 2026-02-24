package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

@Component
@ConditionalOnExpression("${convengine.audit.cache-inspector:false}")
@MustRunAfter(LoadOrCreateConversationStep.class)
@MustRunBefore({ ResetConversationStep.class, AuditUserInputStep.class })
public class CacheInspectAuditStep implements EngineStep {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CacheInspectAuditStep(AuditService auditService) {
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public StepResult execute(EngineSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cache_snapshot", objectMapper.valueToTree(session.getConversation()));
        auditService.audit(ConvEngineAuditStage.CACHE_INSPECTION, session.getConversationId(), payload);
        return new StepResult.Continue();
    }
}
