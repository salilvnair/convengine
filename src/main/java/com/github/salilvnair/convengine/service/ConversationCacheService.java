package com.github.salilvnair.convengine.service;

import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationCacheService {

    private final ConversationRepository conversationRepository;
    private final AsyncConversationPersistenceService asyncPersistence;

    @Cacheable(
            value = "ce_conversation_cache",
            key = "#p0",
            unless = "#result == null"
    )
    public Optional<CeConversation> getConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId);
    }

    @CachePut(value = "ce_conversation_cache", key = "#p0.conversationId")
    public CeConversation saveAndCache(CeConversation conversation) {
        // synchronously update the cache with the new intent/state payload to guarantee
        // consistency across pipelines

        // fire and forget the slow relational block
        asyncPersistence.saveAsync(conversation);

        return conversation;
    }

    @CachePut(value = "ce_conversation_cache", key = "#p0.conversationId")
    public CeConversation createAndCacheSync(CeConversation conversation) {
        // When initially creating, we often need the real DB commit before proceeding
        // So we do a synchronous save, but update the cache.
        return conversationRepository.save(conversation);
    }
}
