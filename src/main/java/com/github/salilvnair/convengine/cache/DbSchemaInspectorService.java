package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.config.ConvEngineSchemaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DbSchemaInspectorService {

    private final JdbcTemplate jdbcTemplate;
    private final ConvEngineSchemaConfig schemaConfig;

    public Map<String, Object> inspect(String schemaName, String tablePrefix) {
        long startedAt = System.currentTimeMillis();
        String requestedSchema = (schemaName == null || schemaName.isBlank())
                ? requireActiveSchema()
                : schemaName.trim();
        String schema = resolveSchemaName(requestedSchema);
        String prefix = (tablePrefix == null) ? "" : tablePrefix.trim();
        String likePattern = prefix + "%";

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> tables = jdbcTemplate.queryForList("""
                SELECT t.table_name
                FROM information_schema.tables t
                WHERE t.table_schema = ?
                  AND t.table_type IN ('BASE TABLE', 'VIEW', 'FOREIGN TABLE')
                  AND t.table_name LIKE ?
                ORDER BY t.table_name
                """, schema, likePattern);
        long tableQueryMs = System.currentTimeMillis() - t0;

        List<String> tableNames = tables.stream()
                .map(r -> String.valueOf(r.get("table_name")))
                .toList();

        long ceCount = tableNames.stream().filter(n -> n != null && n.toLowerCase().startsWith("ce_")).count();
        long zpCount = tableNames.stream().filter(n -> n != null && n.toLowerCase().startsWith("zp_")).count();
        List<String> preview = tableNames.stream().limit(20).collect(Collectors.toList());
        log.info(
                "DB schema inspect: requestedSchema='{}', resolvedSchema='{}', prefix='{}', tableCount={}, ceCount={}, zpCount={}, preview={}",
                requestedSchema, schema, prefix, tableNames.size(), ceCount, zpCount, preview
        );

        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> indexes = new ArrayList<>();
        List<Map<String, Object>> joins = new ArrayList<>();
        List<Map<String, Object>> sequences = new ArrayList<>();
        List<Map<String, Object>> triggers = new ArrayList<>();

        if (!tableNames.isEmpty()) {
            t0 = System.currentTimeMillis();
            columns = jdbcTemplate.queryForList("""
                    SELECT
                      c.table_name,
                      c.column_name,
                      c.data_type,
                      c.is_nullable,
                      c.ordinal_position,
                      CASE WHEN pk.column_name IS NOT NULL THEN TRUE ELSE FALSE END AS is_primary_key,
                      CASE WHEN fk.column_name IS NOT NULL THEN TRUE ELSE FALSE END AS is_foreign_key
                    FROM information_schema.columns c
                    LEFT JOIN (
                      SELECT ku.table_name, ku.column_name
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage ku
                        ON tc.constraint_name = ku.constraint_name
                       AND tc.table_schema = ku.table_schema
                      WHERE tc.constraint_type = 'PRIMARY KEY'
                        AND tc.table_schema = ?
                        AND ku.table_name LIKE ?
                    ) pk
                      ON pk.table_name = c.table_name
                     AND pk.column_name = c.column_name
                    LEFT JOIN (
                      SELECT ku.table_name, ku.column_name
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage ku
                        ON tc.constraint_name = ku.constraint_name
                       AND tc.table_schema = ku.table_schema
                      WHERE tc.constraint_type = 'FOREIGN KEY'
                        AND tc.table_schema = ?
                        AND ku.table_name LIKE ?
                    ) fk
                      ON fk.table_name = c.table_name
                     AND fk.column_name = c.column_name
                    WHERE c.table_schema = ?
                      AND c.table_name LIKE ?
                    ORDER BY c.table_name, c.ordinal_position
                    """, schema, likePattern, schema, likePattern, schema, likePattern);
            long columnQueryMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            joins = jdbcTemplate.queryForList("""
                    SELECT
                      tc.constraint_name,
                      tc.table_name AS source_table,
                      kcu.column_name AS source_column,
                      ccu.table_name AS target_table,
                      ccu.column_name AS target_column
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    JOIN information_schema.constraint_column_usage ccu
                      ON tc.constraint_name = ccu.constraint_name
                     AND tc.table_schema = ccu.table_schema
                    WHERE tc.constraint_type = 'FOREIGN KEY'
                      AND tc.table_schema = ?
                      AND (tc.table_name LIKE ? OR ccu.table_name LIKE ?)
                    ORDER BY tc.table_name, tc.constraint_name
                    """, schema, likePattern, likePattern);
            long joinQueryMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            indexes = jdbcTemplate.queryForList("""
                    SELECT
                      i.tablename AS table_name,
                      i.indexname AS index_name,
                      i.indexdef AS index_def
                    FROM pg_indexes i
                    WHERE i.schemaname = ?
                      AND i.tablename LIKE ?
                    ORDER BY i.tablename, i.indexname
                    """, schema, likePattern);
            long indexQueryMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            sequences = jdbcTemplate.queryForList("""
                    SELECT
                      t.relname AS table_name,
                      a.attname AS column_name,
                      s.relname AS sequence_name
                    FROM pg_class s
                    JOIN pg_depend d
                      ON d.objid = s.oid
                     AND d.deptype = 'a'
                    JOIN pg_class t
                      ON t.oid = d.refobjid
                    JOIN pg_attribute a
                      ON a.attrelid = t.oid
                     AND a.attnum = d.refobjsubid
                    JOIN pg_namespace n
                      ON n.oid = t.relnamespace
                    WHERE s.relkind = 'S'
                      AND n.nspname = ?
                      AND t.relname LIKE ?
                    ORDER BY t.relname, a.attname, s.relname
                    """, schema, likePattern);
            long sequenceQueryMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            triggers = jdbcTemplate.queryForList("""
                    SELECT
                      t.event_object_table AS table_name,
                      t.trigger_name,
                      t.action_timing,
                      t.event_manipulation,
                      t.action_statement
                    FROM information_schema.triggers t
                    WHERE t.trigger_schema = ?
                      AND t.event_object_table LIKE ?
                    ORDER BY t.event_object_table, t.trigger_name
                    """, schema, likePattern);
            long triggerQueryMs = System.currentTimeMillis() - t0;

            log.info(
                    "DB schema inspect timings: schema='{}', prefix='{}', tables={}, tableQueryMs={}, columnQueryMs={}, joinQueryMs={}, indexQueryMs={}, sequenceQueryMs={}, triggerQueryMs={}, totalMs={}",
                    schema, prefix, tableNames.size(), tableQueryMs, columnQueryMs, joinQueryMs, indexQueryMs, sequenceQueryMs,
                    triggerQueryMs, System.currentTimeMillis() - startedAt
            );
        } else {
            log.info(
                    "DB schema inspect timings: schema='{}', prefix='{}', tables=0, tableQueryMs={}, totalMs={}",
                    schema, prefix, tableQueryMs, System.currentTimeMillis() - startedAt
            );
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("prefix", prefix);
        out.put("tableCount", tableNames.size());
        out.put("tables", tables);
        out.put("columns", columns);
        out.put("joins", joins);
        out.put("indexes", indexes);
        out.put("sequences", sequences);
        out.put("triggers", triggers);
        return out;
    }

    private String requireActiveSchema() {
        String active = schemaConfig.getActive();
        if (active == null || active.isBlank()) {
            throw new IllegalStateException("convengine.schema.active must be configured.");
        }
        return active.trim();
    }

    private String resolveSchemaName(String requestedSchema) {
        List<String> exactMatches = jdbcTemplate.query(
                """
                SELECT n.nspname
                FROM pg_namespace n
                WHERE n.nspname = ?
                ORDER BY n.nspname
                LIMIT 1
                """,
                ps -> ps.setString(1, requestedSchema),
                (rs, rowNum) -> rs.getString(1)
        );
        if (!exactMatches.isEmpty()) {
            return exactMatches.get(0);
        }

        List<String> ciMatches = jdbcTemplate.query(
                """
                SELECT n.nspname
                FROM pg_namespace n
                WHERE lower(n.nspname) = lower(?)
                ORDER BY n.nspname
                """,
                ps -> ps.setString(1, requestedSchema),
                (rs, rowNum) -> rs.getString(1)
        );
        if (ciMatches.size() == 1) {
            return ciMatches.get(0);
        }
        if (ciMatches.size() > 1) {
            log.warn("Schema resolution ambiguous for '{}'. candidates={}. Using requested value as-is.",
                    requestedSchema, ciMatches);
            return requestedSchema;
        }

        List<String> allSchemas = jdbcTemplate.query(
                """
                SELECT n.nspname
                FROM pg_namespace n
                ORDER BY n.nspname
                """,
                (rs, rowNum) -> rs.getString(1)
        );
        log.warn("Requested schema '{}' not found. Available schemas={}", requestedSchema,
                allSchemas == null ? Collections.emptyList() : allSchemas);
        return requestedSchema;
    }
}
