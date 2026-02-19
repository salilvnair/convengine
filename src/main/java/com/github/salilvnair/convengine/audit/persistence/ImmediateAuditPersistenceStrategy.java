package com.github.salilvnair.convengine.audit.persistence;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImmediateAuditPersistenceStrategy implements AuditPersistenceStrategy {

    private final AuditDbWriter dbWriter;

    @Override
    public boolean supports(ConvEngineAuditConfig.Mode mode) {
        return mode == ConvEngineAuditConfig.Mode.IMMEDIATE;
    }

    @Override
    public List<CeAudit> persist(CeAudit record) {
        dbWriter.insertSingle(record);
        return List.of(record);
    }

    @Override
    public List<CeAudit> flushPending(UUID conversationId) {
        return Collections.emptyList();
    }
}
