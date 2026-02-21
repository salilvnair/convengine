package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ResponseResolutionStep.class)
@MustRunBefore(PipelineEndGuardStep.class)
public class PersistConversationStep implements EngineStep {

    private final ConversationRepository conversationRepo;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        // --- sanity check ---
        if (session.getPayload() == null) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.PIPELINE_NO_RESPONSE_PAYLOAD,
                    "Engine pipeline ended without payload. ResponseResolutionStep did not run."
            );
        }

        // --- persist conversation ---
        sanitizeConversationForPostgres(session);
        session.getConversation().setStatus("RUNNING");
        session.getConversation().setUpdatedAt(OffsetDateTime.now());
        session.getConversation().setInputParamsJson(session.ejectInputParamsJson());
        conversationRepo.save(session.getConversation());

        // --- build FINAL EngineResult ---
        EngineResult result = new EngineResult(
                session.getIntent(),
                session.getState(),
                session.getPayload(),
                session.getContextJson()
        );

        session.setFinalResult(result);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.FINAL_RESULT, result);
        audit.audit(ConvEngineAuditStage.ENGINE_RETURN, session.getConversationId(), payload);

        return new StepResult.Continue();
    }

    private void sanitizeConversationForPostgres(EngineSession session) {
        if (session.getState() == null || session.getState().isBlank()) {
            session.setState("UNKNOWN");
        }
        session.getConversation().setStateCode(session.getState());

        String contextJson = session.getConversation().getContextJson();
        if (contextJson == null || contextJson.isBlank() || JsonUtil.parseOrNull(contextJson).isNull()) {
            session.getConversation().setContextJson("{}");
            session.setContextJson("{}");
        }

        String sessionJson = session.getConversation().getInputParamsJson();
        if (sessionJson == null || sessionJson.isBlank() || JsonUtil.parseOrNull(sessionJson).isNull()) {
            session.getConversation().setInputParamsJson("{}");
        }

        String assistantJson = session.getConversation().getLastAssistantJson();
        if (assistantJson == null || assistantJson.isBlank()) {
            return;
        }
        if (JsonUtil.parseOrNull(assistantJson).isNull()) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("type", "TEXT");
            wrapped.put("value", assistantJson);
            session.getConversation().setLastAssistantJson(JsonUtil.toJson(wrapped));
        }
    }
}
