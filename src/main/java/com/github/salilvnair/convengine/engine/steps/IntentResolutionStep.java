package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.intent.CompositeIntentResolver;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class IntentResolutionStep implements EngineStep {

    private final CompositeIntentResolver intentResolver;
    private final AuditService audit;
    private final OutputSchemaRepository outputSchemaRepository;

    @Override
    public StepResult execute(EngineSession session) {

        String previousIntent = session.getIntent();

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("previousIntent", previousIntent);
        startPayload.put("intentLocked", session.isIntentLocked());
        startPayload.put("intentLockReason", session.getIntentLockReason());
        audit.audit("INTENT_RESOLVE_START", session.getConversationId(), startPayload);

        if (session.isIntentLocked() || isActiveSchemaCollection(session)) {
            if (!session.isIntentLocked()) {
                session.lockIntent("SCHEMA_INCOMPLETE");
            }
            session.clearClarification();
            if (session.getConversation() != null) {
                session.getConversation().setIntentCode(session.getIntent());
                session.getConversation().setStateCode(session.getState());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("intentLocked", session.isIntentLocked());
            payload.put("intentLockReason", session.getIntentLockReason());
            audit.audit("INTENT_RESOLVE_SKIPPED_SCHEMA_COLLECTION", session.getConversationId(), payload);
            return new StepResult.Continue();
        }

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

    private boolean isActiveSchemaCollection(EngineSession session) {
        if (session.getIntent() == null || session.getIntent().isBlank()) {
            return false;
        }
        try {
            Optional<CeOutputSchema> schema = outputSchemaRepository
                    .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                            session.getIntent(),
                            session.getState()
                    );

            if (schema.isEmpty()) {
                schema = outputSchemaRepository
                        .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                                session.getIntent(),
                                "ANY"
                        );
            }
            if (schema.isEmpty() || schema.get().getJsonSchema() == null) {
                return false;
            }
            return !JsonUtil.isSchemaComplete(schema.get().getJsonSchema(), session.getContextJson());
        } catch (Exception e) {
            return false;
        }
    }
}
