package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.response.type.factory.ResponseTypeResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.repo.ResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class ResponseResolutionStep implements EngineStep {

    private final ResponseRepository responseRepo;
    private final ResponseTypeResolverFactory typeFactory;
    private final AuditService audit;
    private final ConversationHistoryProvider historyProvider;

    @Override
    public StepResult execute(EngineSession session) {

        Optional<CeResponse> responseOptional =
                responseRepo
                        .findFirstByEnabledTrueAndStateCodeAndIntentCodeOrderByPriorityAsc(
                                session.getState(), session.getIntent()
                        ).stream().findAny();
        if(responseOptional.isEmpty()) {
            audit.audit(
                    "RESPONSE_MAPPING_NOT_FOUND",
                    session.getConversationId(),
                    "{\"intent\":\"" + session.getIntent() + "\",\"state\":\"" + session.getState() + "\"}"
            );
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.RESPONSE_MAPPING_NOT_FOUND,
                    "No response found for intent: " + session.getIntent() + " and state: " + session.getState()
            );
        }
        CeResponse resp = responseOptional.get();
        audit.audit(
                "RESOLVE_RESPONSE",
                session.getConversationId(),
                "{\"responseId\":" + resp.getResponseId() + "}"
        );
        List<ConversationTurn> conversationTurns = historyProvider.lastTurns(session.getConversationId(), 10);
        session.setConversationHistory(conversationTurns);
        typeFactory
                .get(resp.getResponseType())
                .resolve(session, resp);

        return new StepResult.Continue();
    }
}
