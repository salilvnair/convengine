package com.github.salilvnair.convengine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.dispatch.AuditEventDispatcher;
import com.github.salilvnair.convengine.audit.dispatch.AuditStageControl;
import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.repo.AuditRepository;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class DbAuditService implements AuditService {

    private final AuditRepository auditRepo;
    private final ConversationRepository conversationRepository;
    private final AuditStageControl stageControl;
    private final AuditEventDispatcher eventDispatcher;
    private final ConvEngineAuditConfig auditConfig;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadLocal<BufferedAuditBatch> deferredBatch = new ThreadLocal<>();

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        try {
            if (!stageControl.shouldAudit(stage, conversationId)) {
                return;
            }
            String normalizedPayload = normalizePayload(stage, conversationId, payloadJson);
            if (isDeferredBulkMode()) {
                bufferAudit(stage, conversationId, normalizedPayload);
                if (shouldFlushDeferred(stage, normalizedPayload)) {
                    flushDeferredBatch(conversationId);
                }
                return;
            }
            CeAudit saved = saveAuditRecord(stage, conversationId, normalizedPayload);
            eventDispatcher.dispatch(saved);
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

    @Override
    public void flushPending(UUID conversationId) {
        if (!isDeferredBulkMode()) {
            return;
        }
        try {
            flushDeferredBatch(conversationId);
        }
        catch (Exception e) {
            log.warn("Deferred audit flush failed convId={} msg={}", conversationId, e.getMessage());
        }
    }

    private void bufferAudit(String stage, UUID conversationId, String payloadJson) {
        BufferedAuditBatch batch = deferredBatch.get();
        if (batch == null || batch.conversationId == null || !conversationId.equals(batch.conversationId)) {
            flushDeferredBatch(batch == null ? null : batch.conversationId);
            batch = new BufferedAuditBatch(conversationId);
            deferredBatch.set(batch);
        }
        batch.records.add(
                CeAudit.builder()
                        .conversationId(conversationId)
                        .stage(stage)
                        .payloadJson(payloadJson)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
        int maxBufferedEvents = Math.max(1, auditConfig.getPersistence().getMaxBufferedEvents());
        if (batch.records.size() >= maxBufferedEvents) {
            flushDeferredBatch(conversationId);
        }
    }

    private boolean shouldFlushDeferred(String stage, String payloadJson) {
        String normalizedStage = stage == null ? "" : stage.trim().toUpperCase();
        if ("STEP_ERROR".equals(normalizedStage)) {
            return true;
        }
        if (auditConfig.getPersistence().getFlushStages() != null
                && auditConfig.getPersistence().getFlushStages().stream()
                .map(s -> s == null ? "" : s.trim().toUpperCase())
                .anyMatch(s -> s.equals(normalizedStage))) {
            return true;
        }
        if (!"STEP_EXIT".equals(normalizedStage)) {
            return false;
        }
        try {
            var root = mapper.readTree(payloadJson);
            String stepName = root.path("step").asText(null);
            String outcome = root.path("outcome").asText(null);
            boolean finalStep =
                    stepName != null
                            && auditConfig.getPersistence().getFinalStepNames() != null
                            && auditConfig.getPersistence().getFinalStepNames().stream()
                            .map(s -> s == null ? "" : s.trim())
                            .anyMatch(s -> s.equals(stepName));
            boolean stopOutcome =
                    auditConfig.getPersistence().isFlushOnStopOutcome()
                            && outcome != null
                            && "STOP".equalsIgnoreCase(outcome);
            return finalStep || stopOutcome;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void flushDeferredBatch(UUID conversationId) {
        BufferedAuditBatch batch = deferredBatch.get();
        if (batch == null || batch.records.isEmpty()) {
            deferredBatch.remove();
            return;
        }
        if (conversationId != null && batch.conversationId != null && !conversationId.equals(batch.conversationId)) {
            return;
        }
        insertBatchJdbc(batch.records);
        for (CeAudit record : batch.records) {
            eventDispatcher.dispatch(record);
        }
        deferredBatch.remove();
    }

    private void insertBatchJdbc(List<CeAudit> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, auditConfig.getPersistence().getJdbcBatchSize());
        String sql = "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, CAST(? AS jsonb), ?)";
        for (int i = 0; i < records.size(); i += batchSize) {
            List<CeAudit> chunk = records.subList(i, Math.min(i + batchSize, records.size()));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    CeAudit row = chunk.get(idx);
                    ps.setObject(1, row.getConversationId());
                    ps.setString(2, row.getStage());
                    ps.setString(3, row.getPayloadJson());
                    ps.setTimestamp(4, Timestamp.from(row.getCreatedAt().toInstant()));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
    }

    private boolean isDeferredBulkMode() {
        return auditConfig.getPersistence() != null
                && ConvEngineAuditConfig.Mode.DEFERRED_BULK == auditConfig.getPersistence().getMode();
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

    private static final class BufferedAuditBatch {
        private final UUID conversationId;
        private final List<CeAudit> records = new ArrayList<>();

        private BufferedAuditBatch(UUID conversationId) {
            this.conversationId = conversationId;
        }
    }
}
