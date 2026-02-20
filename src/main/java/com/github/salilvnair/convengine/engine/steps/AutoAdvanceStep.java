package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.salilvnair.convengine.util.JsonUtil;

/**
 * Computes auto-advance facts used by rule evaluation.
 * This step does not hardcode state/intent transitions.
 */
@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter({
        SchemaExtractionStep.class
})
public class AutoAdvanceStep implements EngineStep {

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getResolvedSchema() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.REASON, "no schema resolved");
            audit.audit(ConvEngineAuditStage.AUTO_ADVANCE_SKIPPED_NO_SCHEMA, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        String schemaJson = session.getResolvedSchema().getJsonSchema();
        String contextJson = session.getContextJson();

        boolean hasAnySchemaValue = JsonUtil.hasAnySchemaValue(contextJson, schemaJson);
        boolean schemaComplete = JsonUtil.isSchemaComplete(schemaJson, contextJson);
        session.setSchemaHasAnyValue(hasAnySchemaValue);
        session.setSchemaComplete(schemaComplete);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.SCHEMA_COMPLETE, schemaComplete);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.HAS_ANY_SCHEMA_VALUE, hasAnySchemaValue);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.AUTO_ADVANCE_FACTS, session.getConversationId(), payload);
        return new StepResult.Continue();
    }
}
