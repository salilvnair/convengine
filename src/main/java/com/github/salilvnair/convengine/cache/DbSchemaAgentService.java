package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateRequest;
import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateResponse;
import com.github.salilvnair.convengine.config.ConvEngineSchemaConfig;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DbSchemaAgentService {

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?i)\\b(delete|drop|alter|truncate|create\\s+table|grant|revoke)\\b"
    );

    private final LlmClient llmClient;
    private final ConvEngineSchemaConfig schemaConfig;

    public DbSchemaAgentGenerateResponse generateSeedSql(DbSchemaAgentGenerateRequest request) {
        List<String> warnings = new ArrayList<>();
        List<DbSchemaAgentGenerateRequest.Row> rows = sanitizeRows(request == null ? null : request.getRows());
        String effectiveSchema = resolveSchema(request == null ? null : request.getSchema());
        if (rows.isEmpty()) {
            return new DbSchemaAgentGenerateResponse(
                    false,
                    "",
                    List.of("No editable rows provided."),
                    "Provide at least one row with table_name and description."
            );
        }

        boolean upsert = request == null || request.getUpsert() == null || request.getUpsert();
        String fallback = buildDeterministicSql(rows, upsert);
        String llmSql = "";

        String systemPrompt = """
                You generate Postgres SQL seed INSERT statements for ce_mcp_schema_knowledge.
                Return SQL only, no markdown and no explanation.

                Hard constraints:
                - Use only INSERT INTO ce_mcp_schema_knowledge (id, table_name, column_name, description, tags)
                - id must be deterministic numeric sequence starting from 1 in the emitted script.
                - Emit one row per input item.
                - If column_name is empty, use NULL.
                - Escape single quotes safely in SQL literals.
                - Do not emit DELETE/DROP/ALTER/TRUNCATE/CREATE TABLE/BEGIN/COMMIT.
                - Keep tags comma separated and lowercase where possible.
                %s
                """.formatted(upsert
                ? "- Add ON CONFLICT (id) DO UPDATE SET table_name = EXCLUDED.table_name, column_name = EXCLUDED.column_name, description = EXCLUDED.description, tags = EXCLUDED.tags."
                : "- Do not add ON CONFLICT clause.");

        String userPrompt = """
                target_schema: %s
                Build SQL insert script for these rows:
                %s
                """.formatted(effectiveSchema, toInputBlock(rows));

        LlmInvocationContext.set(UUID.randomUUID(), "DB_SCHEMA_AGENT", "GENERATE_SEED");
        try {
            llmSql = normalizeSql(llmClient.generateText(systemPrompt + "\n\n" + userPrompt, "{}"));
        } catch (Exception e) {
            warnings.add("LLM generation failed, returned deterministic SQL fallback.");
        } finally {
            LlmInvocationContext.clear();
        }

        if (llmSql.isBlank()) {
            warnings.add("LLM returned empty SQL, used deterministic SQL fallback.");
            return new DbSchemaAgentGenerateResponse(true, fallback, warnings, "Generated using deterministic fallback.");
        }
        if (!isSafeSchemaKnowledgeInsertOnly(llmSql)) {
            warnings.add("LLM SQL failed safety checks, used deterministic SQL fallback.");
            return new DbSchemaAgentGenerateResponse(true, fallback, warnings, "Generated using deterministic fallback.");
        }

        return new DbSchemaAgentGenerateResponse(
                true,
                llmSql,
                warnings,
                warnings.isEmpty() ? "Generated using LLM." : "Generated with guardrails."
        );
    }

    private List<DbSchemaAgentGenerateRequest.Row> sanitizeRows(List<DbSchemaAgentGenerateRequest.Row> input) {
        List<DbSchemaAgentGenerateRequest.Row> rows = new ArrayList<>();
        if (input == null) {
            return rows;
        }
        for (DbSchemaAgentGenerateRequest.Row r : input) {
            if (r == null) {
                continue;
            }
            String table = safe(r.getTableName());
            String description = safe(r.getDescription());
            if (table.isBlank() || description.isBlank()) {
                continue;
            }
            DbSchemaAgentGenerateRequest.Row x = new DbSchemaAgentGenerateRequest.Row();
            x.setTableName(table);
            x.setColumnName(safe(r.getColumnName()));
            x.setRole(safe(r.getRole()));
            x.setDescription(description);
            x.setTags(safe(r.getTags()));
            rows.add(x);
        }
        return rows;
    }

    private String toInputBlock(List<DbSchemaAgentGenerateRequest.Row> rows) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (DbSchemaAgentGenerateRequest.Row r : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(i++).append(") ")
                    .append("table_name=").append(r.getTableName())
                    .append("; column_name=").append(blankAsNullText(r.getColumnName()))
                    .append("; role=").append(blankAsNullText(r.getRole()))
                    .append("; description=").append(r.getDescription())
                    .append("; tags=").append(blankAsNullText(r.getTags()));
        }
        return sb.toString();
    }

    private String buildDeterministicSql(List<DbSchemaAgentGenerateRequest.Row> rows, boolean upsert) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ce_mcp_schema_knowledge (id, table_name, column_name, description, tags)\nVALUES\n");
        for (int i = 0; i < rows.size(); i++) {
            DbSchemaAgentGenerateRequest.Row r = rows.get(i);
            sb.append("  (")
                    .append(i + 1)
                    .append(", ")
                    .append(sqlLiteral(r.getTableName()))
                    .append(", ")
                    .append(sqlNullableLiteral(r.getColumnName()))
                    .append(", ")
                    .append(sqlLiteral(r.getDescription()))
                    .append(", ")
                    .append(sqlNullableLiteral(normalizeTags(r.getTags())))
                    .append(")");
            sb.append(i == rows.size() - 1 ? "\n" : ",\n");
        }
        if (upsert) {
            sb.append("ON CONFLICT (id) DO UPDATE SET\n")
                    .append("  table_name = EXCLUDED.table_name,\n")
                    .append("  column_name = EXCLUDED.column_name,\n")
                    .append("  description = EXCLUDED.description,\n")
                    .append("  tags = EXCLUDED.tags;\n");
        } else {
            sb.append(";\n");
        }
        return sb.toString();
    }

    private boolean isSafeSchemaKnowledgeInsertOnly(String sql) {
        String normalized = normalizeSql(sql);
        if (normalized.isBlank()) {
            return false;
        }
        if (FORBIDDEN_SQL.matcher(normalized).find()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("begin") || lower.contains("commit")) {
            return false;
        }
        if (containsUnsafeStandaloneUpdate(lower)) {
            return false;
        }
        return lower.contains("insert into ce_mcp_schema_knowledge");
    }

    private boolean containsUnsafeStandaloneUpdate(String lowerSql) {
        if (!lowerSql.contains("update")) {
            return false;
        }
        // Permit UPSERT form: INSERT ... ON CONFLICT (...) DO UPDATE ...
        if (lowerSql.contains("on conflict") && lowerSql.contains("do update")) {
            String stripped = lowerSql
                    .replaceAll("(?is)insert\\s+into\\s+ce_mcp_schema_knowledge.*?on\\s+conflict.*?do\\s+update", "");
            return stripped.contains("update");
        }
        return true;
    }

    private String normalizeSql(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceAll("(?s)^```[a-zA-Z0-9]*\\n", "");
            normalized = normalized.replaceAll("(?s)\\n```$", "");
        }
        return normalized.trim();
    }

    private String normalizeTags(String tags) {
        if (tags == null) {
            return "";
        }
        String[] parts = tags.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String t = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (t.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private String sqlLiteral(String value) {
        return "'" + safe(value).replace("'", "''") + "'";
    }

    private String sqlNullableLiteral(String value) {
        String v = safe(value);
        if (v.isBlank()) {
            return "NULL";
        }
        return sqlLiteral(v);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveSchema(String schema) {
        if (schema != null && !schema.isBlank()) {
            return schema.trim();
        }
        String active = schemaConfig.getActive();
        if (active == null || active.isBlank()) {
            throw new IllegalStateException("convengine.schema.active must be configured.");
        }
        return active.trim();
    }

    private String blankAsNullText(String value) {
        String v = safe(value);
        return v.isBlank() ? "<null>" : v;
    }
}
