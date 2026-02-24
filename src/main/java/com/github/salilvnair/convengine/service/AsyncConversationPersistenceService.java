package com.github.salilvnair.convengine.service;

import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncConversationPersistenceService {

    private final ConversationRepository conversationRepository;

    @Async
    public void saveAsync(CeConversation conversation) {
        try {
            conversationRepository.save(conversation);
        } catch (Exception e) {
            log.error("Async Persistence failed for Conversation ID: {}", conversation.getConversationId(), e);
        }
    }
}
