package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.ConversationBootstrapStep;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.service.ConversationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@RequiredArgsConstructor
@Component
@MustRunAfter(LoadOrCreateConversationStep.class)
@ConversationBootstrapStep
public class PersistConversationBootstrapStep implements EngineStep {

    private final ConversationCacheService cacheService;

    @Override
    public StepResult execute(EngineSession session) {
        if (session.getConversation().getCreatedAt() == null) {
            session.getConversation().setCreatedAt(OffsetDateTime.now());
            session.getConversation().setUpdatedAt(OffsetDateTime.now());
            cacheService.saveAndCache(session.getConversation());
        }
        return new StepResult.Continue();
    }
}
