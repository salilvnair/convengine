package com.github.salilvnair.convengine.experimental;

import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationRequest;
import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationResponse;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExperimentalSqlGenerationService {

    private static final String SQL_GENERATION_AGENT_RESOURCE = "prompts/SQL_GENERATION_AGENT.md";
    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?i)\\b(update|delete|drop|alter|truncate|create\\s+table)\\b"
    );
    private static final Pattern INSERT_TABLE_PATTERN = Pattern.compile(
            "(?i)^\\s*insert\\s+into\\s+(?:public\\.)?(ce_[a-z0-9_]+)\\b"
    );
    private static final List<String> NON_TRANSACTIONAL_TABLES_DDL_ORDER = List.of(
            "ce_config",
            "ce_container_config",
            "ce_intent",
            "ce_intent_classifier",
            "ce_mcp_tool",
            "ce_output_schema",
            "ce_policy",
            "ce_prompt_template",
            "ce_response",
            "ce_rule",
            "ce_mcp_db_tool"
    );

    private final LlmClient llmClient;

    public ExperimentalSqlGenerationResponse generate(ExperimentalSqlGenerationRequest request) {
        List<String> warnings = new ArrayList<>();
        String scenario = safe(request.getScenario());
        if (scenario.isBlank()) {
            warnings.add("Scenario is empty.");
            return new ExperimentalSqlGenerationResponse(
                    false,
                    "",
                    warnings,
                    "Provide a non-empty scenario.",
                    new LinkedHashMap<>()
            );
        }
        boolean includeMcp = request.getIncludeMcp() == null || request.getIncludeMcp();
        List<String> expectedTables = expectedTables(includeMcp);
        String agentWiki = loadSqlGenerationAgentWiki();

        String systemPrompt = """
                You generate SQL INSERT scripts for ConvEngine configuration.
                Return SQL ONLY. No markdown. No explanation.
                
                Hard constraints:
                - Use only INSERT INTO statements.
                - Use only ce_* tables.
                - Never emit UPDATE, DELETE, DROP, ALTER, CREATE TABLE, TRUNCATE.
                - Keep values deterministic and readable.
                - Build complete config seed SQL for the scenario using all required non-transactional tables.
                - Add at least one valid INSERT per required table unless explicitly skipped by include_mcp=false.
                - Ensure values align to implemented enums:
                  ce_response.response_type: EXACT | DERIVED
                  ce_response.output_format: TEXT | JSON
                  ce_prompt_template.response_type: TEXT | JSON | SCHEMA_JSON
                  ce_rule.rule_type: EXACT | REGEX | JSON_PATH
                  ce_rule.phase: PIPELINE_RULES | AGENT_POST_INTENT
                  ce_rule.state_code: NULL | ANY | <STATE_CODE>
                  ce_intent_classifier.rule_type: REGEX | CONTAINS | STARTS_WITH
                  action: SET_INTENT | SET_STATE | SET_JSON | GET_CONTEXT | GET_SCHEMA_JSON | GET_SESSION | SET_TASK
                - Prefer uppercase for enum-like fields.
                - Respect foreign-key/dependency order between tables.
                - Add comments only with SQL line comments (--).
                
                Required non-transactional tables for this call:
                %s
                
                ConvEngine SQL generation wiki (authoritative reference):
                %s
                """;

        String userPrompt = """
                Build ce_* INSERT SQL for this scenario:
                scenario: %s
                domain: %s
                constraints: %s
                include_mcp: %s
                
                Output requirements:
                - Emit a single runnable SQL script.
                - Use deterministic IDs where table requires explicit IDs (for example ce_config.config_id).
                - Ensure dependencies are inserted first (for example ce_mcp_tool before ce_mcp_db_tool).
                - Keep script production-safe: no runtime/history table writes.
                - Include meaningful sample prompts for system_prompt and user_prompt fields.
                
                Required table coverage in this call:
                %s
                """.formatted(
                scenario,
                safe(request.getDomain()),
                safe(request.getConstraints()),
                includeMcp ? "true" : "false",
                expectedTablesCsv(expectedTables)
        );

        String raw;
        LlmInvocationContext.set(
                UUID.randomUUID(),
                "EXPERIMENTAL_SQL",
                "GENERATE"
        );
        try {
            raw = llmClient.generateText(
                    systemPrompt.formatted(
                            expectedTablesCsv(expectedTables),
                            agentWiki
                    ) + "\n\n" + userPrompt,
                    "{}"
            );
        } finally {
            LlmInvocationContext.clear();
        }
        String sql = normalizeSql(raw);

        if (sql.isBlank()) {
            warnings.add("LLM returned empty SQL.");
        }
        if (FORBIDDEN_SQL.matcher(sql).find()) {
            warnings.add("Generated SQL contains forbidden statements. Review manually.");
        }
        if (!containsCeInsert(sql)) {
            warnings.add("Generated SQL does not contain any INSERT INTO ce_* statements.");
        }
        List<String> missingExpectedTables = missingExpectedTables(sql, expectedTables);
        if (!missingExpectedTables.isEmpty()) {
            warnings.add("Missing INSERTs for required tables: " + String.join(", ", missingExpectedTables));
        }

        Map<String, String> sqlByTable = splitSqlByTable(sql);
        boolean success = warnings.isEmpty();
        return new ExperimentalSqlGenerationResponse(
                success,
                sql,
                warnings,
                "Experimental output: review before applying in production.",
                sqlByTable
        );
    }

    public byte[] buildSqlZip(ExperimentalSqlGenerationResponse response) {
        Map<String, String> byTable = response.getSqlByTable();
        if (byTable == null || byTable.isEmpty()) {
            byTable = splitSqlByTable(response.getSql());
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            for (Map.Entry<String, String> entry : byTable.entrySet()) {
                String tableName = sanitizeFileName(entry.getKey());
                String fileName = tableName + ".sql";
                writeZipEntry(zos, fileName, entry.getValue());
            }
            writeZipEntry(zos, "seed.sql", safe(response.getSql()));

            String warningsText = response.getWarnings() == null || response.getWarnings().isEmpty()
                    ? "No warnings."
                    : String.join(System.lineSeparator(), response.getWarnings());
            writeZipEntry(zos, "warnings.txt", warningsText);
            writeZipEntry(zos, "note.txt", safe(response.getNote()));

            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build SQL zip: " + e.getMessage(), e);
        }
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

    private boolean containsCeInsert(String sql) {
        return !insertedTables(sql).isEmpty();
    }

    private List<String> expectedTables(boolean includeMcp) {
        List<String> tables = new ArrayList<>();
        for (String table : NON_TRANSACTIONAL_TABLES_DDL_ORDER) {
            if (!includeMcp && ("ce_mcp_tool".equalsIgnoreCase(table) || "ce_mcp_db_tool".equalsIgnoreCase(table))) {
                continue;
            }
            tables.add(table);
        }
        return tables;
    }

    private String expectedTablesCsv(List<String> tables) {
        return String.join(", ", tables);
    }

    private List<String> missingExpectedTables(String sql, List<String> expectedTables) {
        Set<String> present = insertedTables(sql);
        List<String> missing = new ArrayList<>();
        for (String expectedTable : expectedTables) {
            if (!present.contains(expectedTable.toLowerCase(Locale.ROOT))) {
                missing.add(expectedTable);
            }
        }
        return missing;
    }

    private Set<String> insertedTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return tables;
        }
        for (String line : sql.split("\\R")) {
            Matcher matcher = INSERT_TABLE_PATTERN.matcher(line);
            if (matcher.find()) {
                tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private Map<String, String> splitSqlByTable(String sql) {
        Map<String, StringBuilder> grouped = new LinkedHashMap<>();
        for (String tableName : NON_TRANSACTIONAL_TABLES_DDL_ORDER) {
            grouped.put(tableName, new StringBuilder());
        }
        grouped.put("misc", new StringBuilder());

        if (sql == null || sql.isBlank()) {
            return grouped.entrySet().stream()
                    .filter(e -> e.getValue().length() > 0)
                    .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().toString().trim()), Map::putAll);
        }

        for (String statement : splitStatements(sql)) {
            String table = extractInsertTable(statement);
            String key = table == null ? "misc" : table.toLowerCase(Locale.ROOT);
            StringBuilder bucket = grouped.computeIfAbsent(key, k -> new StringBuilder());
            if (bucket.length() > 0) {
                bucket.append(System.lineSeparator()).append(System.lineSeparator());
            }
            bucket.append(statement.trim());
            if (!statement.trim().endsWith(";")) {
                bucket.append(";");
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : grouped.entrySet()) {
            String value = entry.getValue().toString().trim();
            if (!value.isBlank()) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            current.append(ch);
            if (ch == '\'') {
                boolean escaped = i + 1 < sql.length() && sql.charAt(i + 1) == '\'';
                if (escaped) {
                    current.append(sql.charAt(i + 1));
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (ch == ';' && !inSingleQuote) {
                String statement = current.toString().trim();
                if (!statement.isBlank()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isBlank()) {
            statements.add(tail);
        }
        return statements;
    }

    private String extractInsertTable(String statement) {
        String normalized = statement == null ? "" : statement.trim();
        normalized = normalized.replaceAll("(?m)^\\s*--.*$", "").trim();
        Matcher matcher = INSERT_TABLE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        byte[] bytes = safe(content).getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    private String sanitizeFileName(String input) {
        if (input == null || input.isBlank()) {
            return "misc";
        }
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private String loadSqlGenerationAgentWiki() {
        return loadClasspathResource(SQL_GENERATION_AGENT_RESOURCE, "SQL generation wiki");
    }

    private String loadClasspathResource(String resourcePath, String label) {
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                return label + " not found at classpath:" + resourcePath;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "Failed to load " + label + ": " + e.getMessage();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
