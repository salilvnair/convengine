package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationRepository
        extends JpaRepository<CeConversation, UUID> {
}
