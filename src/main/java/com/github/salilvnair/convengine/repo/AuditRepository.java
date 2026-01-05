package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<CeAudit, Long> {

    List<CeAudit> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
