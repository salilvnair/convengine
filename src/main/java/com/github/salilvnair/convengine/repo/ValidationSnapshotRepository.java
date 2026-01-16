package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeValidationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ValidationSnapshotRepository
        extends JpaRepository<CeValidationSnapshot, Long> {

    Optional<CeValidationSnapshot> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
