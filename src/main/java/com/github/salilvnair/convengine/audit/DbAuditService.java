package com.github.salilvnair.convengine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        try {
            saveAuditRecord(stage, conversationId, sanitizeJson(payloadJson));
        } catch (Exception e) {
            log.error("Failed to save audit record for conversationId: {} at stage: {}, payload: {}", conversationId, stage, payloadJson, e);
            throw new ConversationEngineException(
                   ConversationEngineErrorCode.AUDIT_SAVE_FAILED,
                  "Failed to save audit record for conversationId: " + conversationId + " at stage: " + stage
            );
        }
    }

    private String sanitizeJson(String payloadJson) {
        try {
            if (payloadJson == null || payloadJson.isBlank()) {
                return "{}";
            }
            mapper.readTree(payloadJson); // validates JSON
            return payloadJson;
        } catch (Exception e) {
            try {
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("raw_payload", payloadJson == null ? "" : payloadJson);
                fallback.put("note", "payload_was_not_valid_json");
                return mapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"raw_payload\":\"<unserializable>\",\"note\":\"payload_was_not_valid_json\"}";
            }
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
