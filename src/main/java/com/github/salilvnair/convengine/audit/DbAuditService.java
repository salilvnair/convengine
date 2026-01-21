package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class DbAuditService implements AuditService {

    private final AuditRepository auditRepo;

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        try {
            saveAuditRecord(stage, conversationId, payloadJson);
        } catch (Exception e) {
            log.error("Failed to save audit record for conversationId: {} at stage: {}, payload: {}", conversationId, stage, payloadJson, e);
            throw new ConversationEngineException(
                   ConversationEngineErrorCode.AUDIT_SAVE_FAILED,
                  "Failed to save audit record for conversationId: " + conversationId + " at stage: " + stage
            );
        }
    }

    private void saveAuditRecord(String stage, UUID conversationId, String payloadJson) {
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
