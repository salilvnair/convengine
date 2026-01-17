package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
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

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ResponseResolutionStep.class)
@MustRunBefore(PipelineEndGuardStep.class)
public class PersistConversationStep implements EngineStep {

    private final ConversationRepository conversationRepo;
    private final AuditService audit;
    private final ObjectMapper mapper;

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
        session.getConversation().setStatus("RUNNING");
        session.getConversation().setUpdatedAt(OffsetDateTime.now());
        conversationRepo.save(session.getConversation());

        // --- build FINAL EngineResult ---
        EngineResult result = new EngineResult(
                session.getIntent(),
                session.getState(),
                session.getPayload(),
                session.getContextJson()
        );

        session.setFinalResult(result);

        audit.audit("ENGINE_RETURN", session.getConversationId(),
                "{\"intent\":\"" + JsonUtil.escape(session.getIntent()) +
                        "\",\"state\":\"" + JsonUtil.escape(session.getState()) + "\"}");

        return new StepResult.Continue();
    }
}
