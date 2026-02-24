package com.github.salilvnair.convengine.service;

import com.github.salilvnair.convengine.entity.CeConversationHistory;
import com.github.salilvnair.convengine.repo.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class AsyncConversationHistoryPersistenceService {

    private final ConversationHistoryRepository conversationHistoryRepository;

    @Async
    public void saveHistory(CeConversationHistory history) {
        try {
            conversationHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to asynchronously persist CeConversationHistory for conversation {}: {}",
                    history.getConversationId(), e.getMessage());
        }
    }
}
