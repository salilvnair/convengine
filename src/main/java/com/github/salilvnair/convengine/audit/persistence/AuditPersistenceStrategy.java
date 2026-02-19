package com.github.salilvnair.convengine.audit.persistence;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.entity.CeAudit;

import java.util.List;
import java.util.UUID;

public interface AuditPersistenceStrategy {

    boolean supports(ConvEngineAuditConfig.Mode mode);

    List<CeAudit> persist(CeAudit record);

    List<CeAudit> flushPending(UUID conversationId);
}
