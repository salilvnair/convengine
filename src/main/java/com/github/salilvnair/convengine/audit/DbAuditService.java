package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class DbAuditService implements AuditService {

    private final AuditRepository auditRepo;

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        auditRepo.save(
                CeAudit.builder()
                        .conversationId(conversationId)
                        .stage(stage)
                        .payloadJson(payloadJson)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }
}
