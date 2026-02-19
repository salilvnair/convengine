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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    // SQLite JDBC timestamp parsing expects local timestamp text without zone offset.
    private static final DateTimeFormatter SQLITE_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private volatile DbDialect dbDialect;
    private volatile boolean sqliteTimestampNormalized;

    @PostConstruct
    void initDialectAndNormalization() {
        try {
            resolveDialect();
        }
        catch (Exception e) {
            log.warn("Audit DB dialect detection/normalization skipped: {}", e.getMessage());
        }
    }

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
            addInputParamsMeta(root, meta, conversation);
            addUserInputParamsMeta(root, meta);
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
                addInputParamsMeta(fallback, meta, conversation);
                addUserInputParamsMeta(fallback, meta);
                return mapper.writeValueAsString(fallback);
            }
            catch (Exception ignored) {
                return "{\"raw_payload\":\"<unserializable>\",\"note\":\"payload_was_not_valid_json\"}";
            }
        }
    }

    private CeAudit saveAuditRecord(String stage, UUID conversationId, String payloadJson) {
        CeAudit record = CeAudit.builder()
                .conversationId(conversationId)
                .stage(stage)
                .payloadJson(payloadJson)
                .createdAt(OffsetDateTime.now())
                .build();
        if (resolveDialect() == DbDialect.SQLITE) {
            insertSingleJdbc(record);
            return record;
        }
        return auditRepo.save(record);
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
        DbDialect dialect = resolveDialect();
        String sql = dialect == DbDialect.POSTGRES
                ? "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, CAST(? AS jsonb), ?)"
                : "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, ?, ?)";
        for (int i = 0; i < records.size(); i += batchSize) {
            List<CeAudit> chunk = records.subList(i, Math.min(i + batchSize, records.size()));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    CeAudit row = chunk.get(idx);
                    bindAuditInsert(ps, row, dialect);
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

    private DbDialect resolveDialect() {
        DbDialect cached = dbDialect;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (dbDialect != null) {
                return dbDialect;
            }
            String url = null;
            try {
                if (jdbcTemplate.getDataSource() != null) {
                    try (var conn = jdbcTemplate.getDataSource().getConnection()) {
                        url = conn.getMetaData().getURL();
                    }
                }
            }
            catch (Exception ignored) {
            }
            if (url == null) {
                dbDialect = DbDialect.OTHER;
                return dbDialect;
            }
            String normalized = url.toLowerCase(Locale.ROOT);
            if (normalized.contains(":sqlite:")) {
                dbDialect = DbDialect.SQLITE;
                normalizeSqliteTimestampsOnce();
            }
            else if (normalized.contains(":postgresql:")) {
                dbDialect = DbDialect.POSTGRES;
            }
            else if (normalized.contains(":oracle:")) {
                dbDialect = DbDialect.ORACLE;
            }
            else {
                dbDialect = DbDialect.OTHER;
            }
            return dbDialect;
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

    private void addInputParamsMeta(ObjectNode root, ObjectNode meta, CeConversation conversation) {
        try {
            ObjectNode resolved = resolveInputParamsNode(root, conversation);
            if (resolved == null) {
                return;
            }
            meta.set("inputParams", resolved);
        }
        catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveInputParamsNode(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getInputParams() != null && !session.getInputParams().isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>(session.getInputParams());
            var node = mapper.valueToTree(copy);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        }
        if (root != null) {
            var payloadNode = root.path("inputParams");
            if (payloadNode.isObject() && payloadNode.size() > 0) {
                return ((ObjectNode) payloadNode).deepCopy();
            }
        }
        if (conversation != null && conversation.getInputParamsJson() != null && !conversation.getInputParamsJson().isBlank()) {
            try {
                var conversationNode = mapper.readTree(conversation.getInputParamsJson());
                if (conversationNode.isObject() && conversationNode.size() > 0) {
                    return (ObjectNode) conversationNode;
                }
            }
            catch (Exception ignored) {
                // invalid persisted json should not break audit flow
            }
        }
        return null;
    }

    private void addUserInputParamsMeta(ObjectNode root, ObjectNode meta) {
        try {
            ObjectNode resolved = resolveUserInputParamsNode(root);
            if (resolved == null) {
                return;
            }
            meta.set("userInputParams", resolved);
        }
        catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveUserInputParamsNode(ObjectNode root) {
        EngineSession session = AuditSessionContext.get();
        if (session != null
                && session.getEngineContext() != null
                && session.getEngineContext().getUserInputParams() != null
                && !session.getEngineContext().getUserInputParams().isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>(session.getEngineContext().getUserInputParams());
            var node = mapper.valueToTree(copy);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        }
        if (root != null) {
            var payloadNode = root.path("userInputParams");
            if (payloadNode.isObject() && !payloadNode.isEmpty()) {
                return ((ObjectNode) payloadNode).deepCopy();
            }
        }
        return null;
    }

    private void insertSingleJdbc(CeAudit row) {
        DbDialect dialect = resolveDialect();
        String sql = dialect == DbDialect.POSTGRES
                ? "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, CAST(? AS jsonb), ?)"
                : "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, ps -> bindAuditInsert(ps, row, dialect));
    }

    private void bindAuditInsert(PreparedStatement ps, CeAudit row, DbDialect dialect) throws SQLException {
        if (row.getConversationId() == null) {
            ps.setObject(1, null);
        }
        else if (dialect == DbDialect.POSTGRES) {
            ps.setObject(1, row.getConversationId());
        }
        else {
            ps.setString(1, row.getConversationId().toString());
        }
        ps.setString(2, row.getStage());
        ps.setString(3, row.getPayloadJson());
        if (dialect == DbDialect.SQLITE) {
            ps.setString(4, SQLITE_TS_FMT.format(row.getCreatedAt().toLocalDateTime()));
        }
        else {
            ps.setObject(4, row.getCreatedAt());
        }
    }

    private void normalizeSqliteTimestampsOnce() {
        if (sqliteTimestampNormalized) {
            return;
        }
        synchronized (this) {
            if (sqliteTimestampNormalized) {
                return;
            }
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                try (Statement st = con.createStatement()) {
                    st.execute("PRAGMA foreign_keys=OFF");
                    normalizeSqliteConversationIdColumn(st, "ce_conversation");
                    normalizeSqliteConversationIdColumn(st, "ce_audit");
                    normalizeSqliteConversationIdColumn(st, "ce_llm_call_log");
                    normalizeSqliteConversationIdColumn(st, "ce_validation_snapshot");
                    normalizeSqliteTimestampColumn(st, "ce_audit", "created_at");
                    normalizeSqliteTimestampColumn(st, "ce_conversation", "created_at");
                    normalizeSqliteTimestampColumn(st, "ce_conversation", "updated_at");
                    normalizeSqliteTimestampColumn(st, "ce_llm_call_log", "created_at");
                    normalizeSqliteTimestampColumn(st, "ce_validation_snapshot", "created_at");
                    st.execute("PRAGMA foreign_keys=ON");
                }
                return null;
            });
            sqliteTimestampNormalized = true;
        }
    }

    private void normalizeSqliteTimestampColumn(Statement st, String table, String column) {
        String sql = "UPDATE " + table + " SET " + column + " = " +
                "CASE WHEN LENGTH(TRIM(" + column + ")) >= 13 " +
                "THEN STRFTIME('%Y-%m-%d %H:%M:%f', CAST(" + column + " AS REAL)/1000.0, 'unixepoch') " +
                "ELSE STRFTIME('%Y-%m-%d %H:%M:%f', CAST(" + column + " AS REAL), 'unixepoch') END " +
                "WHERE " + column + " IS NOT NULL AND TRIM(" + column + ") <> '' AND TRIM(" + column + ") GLOB '[0-9]*'";
        try {
            int updated = st.executeUpdate(sql);
            if (updated > 0) {
                log.info("Normalized {} legacy epoch timestamp rows in {}.{}", updated, table, column);
            }
        } catch (Exception ignored) {
            // table/column may not exist for some deployments; ignore safely
        }
    }

    private void normalizeSqliteConversationIdColumn(Statement st, String table) {
        String sql = "UPDATE " + table + " SET conversation_id = LOWER(" +
                "SUBSTR(HEX(conversation_id),1,8) || '-' || " +
                "SUBSTR(HEX(conversation_id),9,4) || '-' || " +
                "SUBSTR(HEX(conversation_id),13,4) || '-' || " +
                "SUBSTR(HEX(conversation_id),17,4) || '-' || " +
                "SUBSTR(HEX(conversation_id),21,12)) " +
                "WHERE conversation_id IS NOT NULL AND TYPEOF(conversation_id)='blob' AND LENGTH(HEX(conversation_id))=32";
        try {
            int updated = st.executeUpdate(sql);
            if (updated > 0) {
                log.info("Normalized {} legacy BLOB conversation_id rows in {}", updated, table);
            }
        } catch (Exception ignored) {
            // table may not exist for some deployments; ignore safely
        }
    }

    private static final class BufferedAuditBatch {
        private final UUID conversationId;
        private final List<CeAudit> records = new ArrayList<>();

        private BufferedAuditBatch(UUID conversationId) {
            this.conversationId = conversationId;
        }
    }

    private enum DbDialect {
        SQLITE,
        POSTGRES,
        ORACLE,
        OTHER
    }
}
