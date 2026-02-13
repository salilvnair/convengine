package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.ConversationBootstrapStep;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@RequiredArgsConstructor
@Component
@MustRunAfter(LoadOrCreateConversationStep.class)
@ConversationBootstrapStep
public class PersistConversationBootstrapStep implements EngineStep {

    private final ConversationRepository conversationRepo;

    @Override
    public StepResult execute(EngineSession session) {
        if (session.getConversation().getCreatedAt() == null) {
            session.getConversation().setCreatedAt(OffsetDateTime.now());
            session.getConversation().setUpdatedAt(OffsetDateTime.now());
            conversationRepo.save(session.getConversation());
        }
        return new StepResult.Continue();
    }
}
