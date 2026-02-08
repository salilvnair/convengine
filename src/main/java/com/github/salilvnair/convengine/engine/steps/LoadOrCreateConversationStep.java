package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class LoadOrCreateConversationStep implements EngineStep {

    private final ConversationRepository conversationRepo;

    @Override
    public StepResult execute(EngineSession session) {
        UUID id = session.getConversationId();
        CeConversation convo = conversationRepo.findById(id).orElseGet(() -> createNewConversation(id, conversationRepo));

        convo.setLastUserText(session.getUserText());
        convo.setUpdatedAt(OffsetDateTime.now());

        session.setConversation(convo);
        session.syncFromConversation();

        return new StepResult.Continue();
    }

    private static CeConversation createNewConversation(UUID id, ConversationRepository repo) {
        CeConversation c = CeConversation.builder()
                .conversationId(id)
                .status("RUNNING")
                .stateCode("UNKNOWN")
                .contextJson("{}")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return repo.save(c);
    }
}
