package com.github.salilvnair.convengine.util;

import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class AuditWriter {

    private final AuditRepository auditRepository;

    public void write(
            UUID conversationId,
            String stage,
            Map<String, Object> payload
    ) {
        auditRepository.save(
                CeAudit.builder()
                        .conversationId(conversationId)
                        .stage(stage)
                        .payloadJson(JsonUtil.toJson(payload))
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }
}
