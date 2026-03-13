package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeSemanticQueryFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SemanticQueryFailureRepository extends JpaRepository<CeSemanticQueryFailure, Long> {
    Optional<CeSemanticQueryFailure> findFirstByConversationIdAndQuestionAndCorrectSqlIsNullOrderByCreatedAtDesc(UUID conversationId, String question);
}
