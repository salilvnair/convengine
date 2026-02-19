package com.github.salilvnair.convengine.audit.persistence;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditDbWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ConvEngineAuditConfig auditConfig;
    private static final DateTimeFormatter SQLITE_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private volatile DbDialect dbDialect;
    private volatile boolean sqliteTimestampNormalized;

    @PostConstruct
    void initDialectAndNormalization() {
        try {
            resolveDialect();
        } catch (Exception e) {
            log.warn("Audit DB dialect detection/normalization skipped: {}", e.getMessage());
        }
    }

    public void insertSingle(CeAudit row) {
        DbDialect dialect = resolveDialect();
        String sql = dialect == DbDialect.POSTGRES
                ? "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, CAST(? AS jsonb), ?)"
                : "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, ps -> bindAuditInsert(ps, row, dialect));
    }

    public void insertBatch(List<CeAudit> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int configuredBatchSize = auditConfig.getPersistence() == null ? 200 : auditConfig.getPersistence().getJdbcBatchSize();
        int batchSize = Math.max(1, configuredBatchSize);
        DbDialect dialect = resolveDialect();
        String sql = dialect == DbDialect.POSTGRES
                ? "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, CAST(? AS jsonb), ?)"
                : "INSERT INTO ce_audit (conversation_id, stage, payload_json, created_at) VALUES (?, ?, ?, ?)";
        for (int i = 0; i < records.size(); i += batchSize) {
            List<CeAudit> chunk = records.subList(i, Math.min(i + batchSize, records.size()));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    bindAuditInsert(ps, chunk.get(idx), dialect);
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
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
            } catch (Exception ignored) {
            }
            if (url == null) {
                dbDialect = DbDialect.OTHER;
                return dbDialect;
            }
            String normalized = url.toLowerCase(Locale.ROOT);
            if (normalized.contains(":sqlite:")) {
                dbDialect = DbDialect.SQLITE;
                normalizeSqliteTimestampsOnce();
            } else if (normalized.contains(":postgresql:")) {
                dbDialect = DbDialect.POSTGRES;
            } else if (normalized.contains(":oracle:")) {
                dbDialect = DbDialect.ORACLE;
            } else {
                dbDialect = DbDialect.OTHER;
            }
            return dbDialect;
        }
    }

    private void bindAuditInsert(PreparedStatement ps, CeAudit row, DbDialect dialect) throws SQLException {
        if (row.getConversationId() == null) {
            ps.setObject(1, null);
        } else if (dialect == DbDialect.POSTGRES) {
            ps.setObject(1, row.getConversationId());
        } else {
            ps.setString(1, row.getConversationId().toString());
        }
        ps.setString(2, row.getStage());
        ps.setString(3, row.getPayloadJson());
        if (dialect == DbDialect.SQLITE) {
            ps.setString(4, SQLITE_TS_FMT.format(row.getCreatedAt().toLocalDateTime()));
        } else {
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
                    normalizeSqliteConversationIdColumn(st, "ce_conversation_history");
                    normalizeSqliteConversationIdColumn(st, "ce_llm_call_log");
                    normalizeSqliteConversationIdColumn(st, "ce_validation_snapshot");
                    normalizeSqliteTimestampColumn(st, "ce_audit", "created_at");
                    normalizeSqliteTimestampColumn(st, "ce_conversation_history", "created_at");
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

    private enum DbDialect {
        SQLITE,
        POSTGRES,
        ORACLE,
        OTHER
    }
}
