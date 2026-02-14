package com.github.salilvnair.convengine.engine.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class EngineSessionFactory {

    private final ObjectMapper mapper;
    private final ConversationRepository conversationRepository;

    public EngineSession open(EngineContext ctx) {
        EngineSession session = new EngineSession(ctx, mapper);
        CeConversation conversation = ensureConversationBootstrap(session.getConversationId());
        session.setConversation(conversation);
        session.syncFromConversation();
        return session;
    }

    private CeConversation ensureConversationBootstrap(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseGet(() -> createMinimalConversation(conversationId));
    }

    private CeConversation createMinimalConversation(UUID conversationId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            CeConversation conversation = CeConversation.builder()
                    .conversationId(conversationId)
                    .status("RUNNING")
                    .stateCode("UNKNOWN")
                    .contextJson("{}")
                    .inputParamsJson("{}")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            return conversationRepository.save(conversation);
        }
        catch (DataIntegrityViolationException ignored) {
            // Another concurrent request created the row first.
            return conversationRepository.findById(conversationId)
                    .orElseThrow(() -> ignored);
        }
    }
}
