package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeConversationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationHistoryRepository extends JpaRepository<CeConversationHistory, Long> {

    List<CeConversationHistory> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
