package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.service.ConversationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class LoadOrCreateConversationStep implements EngineStep {

    private final ConversationCacheService cacheService;

    @Override
    public StepResult execute(EngineSession session) {
        UUID id = session.getConversationId();
        CeConversation convo = cacheService.getConversation(id)
                .orElseGet(() -> createNewConversation(id, cacheService));

        convo.setLastUserText(session.getUserText());
        convo.setUpdatedAt(OffsetDateTime.now());

        session.setConversation(convo);
        session.syncFromConversation();

        return new StepResult.Continue();
    }

    private static CeConversation createNewConversation(UUID id, ConversationCacheService cacheService) {
        CeConversation c = CeConversation.builder()
                .conversationId(id)
                .status("RUNNING")
                .stateCode("UNKNOWN")
                .contextJson("{}")
                .inputParamsJson("{}")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return cacheService.createAndCacheSync(c);
    }
}
