package com.github.salilvnair.convengine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.AuditRepository;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class DbAuditService implements AuditService {

    private final AuditRepository auditRepo;
    private final ConversationRepository conversationRepository;
    private final List<AuditEventListener> listeners;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        try {
            CeAudit saved = saveAuditRecord(stage, conversationId, normalizePayload(stage, conversationId, payloadJson));
            publish(saved);
        } catch (Exception e) {
            log.error("Failed to save audit record for conversationId: {} at stage: {}, payload: {}", conversationId, stage, payloadJson, e);
            throw new ConversationEngineException(
                   ConversationEngineErrorCode.AUDIT_SAVE_FAILED,
                  "Failed to save audit record for conversationId: " + conversationId + " at stage: " + stage
            );
        }
    }

    private String normalizePayload(String stage, UUID conversationId, String payloadJson) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (payloadJson != null && !payloadJson.isBlank()) {
                var payloadNode = mapper.readTree(payloadJson);
                if (payloadNode.isObject()) {
                    root.setAll((ObjectNode) payloadNode);
                }
                else {
                    root.set("value", payloadNode);
                }
            }
            ObjectNode meta = root.withObject("_meta");
            meta.put("stage", stage);
            meta.put("conversationId", String.valueOf(conversationId));
            meta.put("emittedAt", OffsetDateTime.now().toString());
            CeConversation conversation = conversationRepository.findById(conversationId).orElse(null);
            String intent = resolveIntent(root, conversation);
            String state = resolveState(root, conversation);
            if (intent != null && !intent.isBlank()) {
                meta.put("intent", intent);
            }
            if (state != null && !state.isBlank()) {
                meta.put("state", state);
            }
            return mapper.writeValueAsString(root);
        }
        catch (Exception e) {
            try {
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("raw_payload", payloadJson == null ? "" : payloadJson);
                fallback.put("note", "payload_was_not_valid_json");
                ObjectNode meta = fallback.withObject("_meta");
                meta.put("stage", stage);
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("emittedAt", OffsetDateTime.now().toString());
                CeConversation conversation = conversationRepository.findById(conversationId).orElse(null);
                if (conversation != null) {
                    if (conversation.getIntentCode() != null && !conversation.getIntentCode().isBlank()) {
                        meta.put("intent", conversation.getIntentCode());
                    }
                    if (conversation.getStateCode() != null && !conversation.getStateCode().isBlank()) {
                        meta.put("state", conversation.getStateCode());
                    }
                }
                return mapper.writeValueAsString(fallback);
            }
            catch (Exception ignored) {
                return "{\"raw_payload\":\"<unserializable>\",\"note\":\"payload_was_not_valid_json\"}";
            }
        }
    }

    private CeAudit saveAuditRecord(String stage, UUID conversationId, String payloadJson) {
        return auditRepo.save(
                CeAudit.builder()
                        .conversationId(conversationId)
                        .stage(stage)
                        .payloadJson(payloadJson)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }

    private void publish(CeAudit audit) {
        for (AuditEventListener listener : listeners) {
            try {
                listener.onAudit(audit);
            } catch (Exception e) {
                log.warn(
                        "Audit listener failed listener={} convId={} stage={} msg={}",
                        listener.getClass().getSimpleName(),
                        audit.getConversationId(),
                        audit.getStage(),
                        e.getMessage()
                );
            }
        }
    }

    private String resolveIntent(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getIntent() != null && !session.getIntent().isBlank()) {
            return session.getIntent();
        }
        String fromPayload = readText(root, "intent");
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload;
        }
        return conversation == null ? null : conversation.getIntentCode();
    }

    private String resolveState(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getState() != null && !session.getState().isBlank()) {
            return session.getState();
        }
        String fromPayload = readText(root, "state");
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload;
        }
        return conversation == null ? null : conversation.getStateCode();
    }

    private String readText(ObjectNode root, String field) {
        try {
            if (root == null || field == null) {
                return null;
            }
            var node = root.path(field);
            if (!node.isTextual()) {
                node = root.path("_meta").path(field);
            }
            return node.isTextual() ? node.asText() : null;
        }
        catch (Exception ignored) {
            return null;
        }
    }
}
