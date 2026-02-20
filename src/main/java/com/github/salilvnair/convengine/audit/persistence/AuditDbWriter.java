package com.github.salilvnair.convengine.audit.persistence;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    @PostConstruct
    void initDialect() {
        try {
            resolveDialect();
        } catch (Exception e) {
            log.warn("Audit DB dialect detection skipped: {}", e.getMessage());
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

    private enum DbDialect {
        SQLITE,
        POSTGRES,
        ORACLE,
        OTHER
    }
}
