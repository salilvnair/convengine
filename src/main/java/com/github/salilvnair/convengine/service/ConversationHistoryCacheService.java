package com.github.salilvnair.convengine.service;

import com.github.salilvnair.convengine.entity.CeConversationHistory;
import com.github.salilvnair.convengine.repo.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationHistoryCacheService {

    private final ConversationHistoryRepository conversationHistoryRepository;

    @Cacheable(value = "ce_conversation_history_cache", key = "#p0")
    public List<CeConversationHistory> getHistory(UUID conversationId) {
        return conversationHistoryRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 500)).getContent();
    }

    @CachePut(value = "ce_conversation_history_cache", key = "#p0")
    public List<CeConversationHistory> updateHistoryCache(UUID conversationId,
            List<CeConversationHistory> updatedHistory) {
        return updatedHistory;
    }
}
