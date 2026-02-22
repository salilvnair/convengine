package com.github.salilvnair.convengine.audit.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeferredBulkAuditPersistenceStrategy implements AuditPersistenceStrategy {

    private final ConvEngineAuditConfig auditConfig;
    private final AuditDbWriter dbWriter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadLocal<BufferedAuditBatch> deferredBatch = new ThreadLocal<>();

    @Override
    public boolean supports(ConvEngineAuditConfig.Mode mode) {
        return mode == ConvEngineAuditConfig.Mode.DEFERRED_BULK;
    }

    @Override
    public List<CeAudit> persist(CeAudit record) {
        BufferedAuditBatch batch = deferredBatch.get();
        if (batch == null || !record.getConversationId().equals(batch.conversationId)) {
            flushPending(batch == null ? null : batch.conversationId);
            batch = new BufferedAuditBatch(record.getConversationId());
            deferredBatch.set(batch);
        }
        batch.records.add(record);
        int maxBufferedEvents = Math.max(1, auditConfig.getPersistence().getMaxBufferedEvents());
        if (batch.records.size() >= maxBufferedEvents || shouldFlushDeferred(record.getStage(), record.getPayloadJson())) {
            return flushPending(record.getConversationId());
        }
        return Collections.emptyList();
    }

    @Override
    public List<CeAudit> flushPending(UUID conversationId) {
        BufferedAuditBatch batch = deferredBatch.get();
        if (batch == null || batch.records.isEmpty()) {
            deferredBatch.remove();
            return Collections.emptyList();
        }
        if (conversationId != null && batch.conversationId != null && !conversationId.equals(batch.conversationId)) {
            return Collections.emptyList();
        }
        List<CeAudit> flushed = new ArrayList<>(batch.records);
        dbWriter.insertBatch(flushed);
        deferredBatch.remove();
        return flushed;
    }

    private boolean shouldFlushDeferred(String stage, String payloadJson) {
        String normalizedStage = stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT);
        if ("STEP_ERROR".equals(normalizedStage)) {
            return true;
        }
        if (auditConfig.getPersistence().getFlushStages() != null
                && auditConfig.getPersistence().getFlushStages().stream()
                .map(s -> s == null ? "" : s.trim().toUpperCase(Locale.ROOT))
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
        } catch (Exception ignored) {
            return false;
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
