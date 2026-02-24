package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeConversationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationHistoryRepository extends JpaRepository<CeConversationHistory, Long> {

    Page<CeConversationHistory>
    findByConversationIdOrderByCreatedAtDesc(
            UUID conversationId,
            Pageable pageable
    );
}
