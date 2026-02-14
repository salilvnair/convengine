package com.github.salilvnair.convengine.experimental;

import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationRequest;
import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationResponse;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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
                    "Provide a non-empty scenario."
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
                
                SQL generation wiki reference (same as system prompt):
                %s
                """.formatted(
                scenario,
                safe(request.getDomain()),
                safe(request.getConstraints()),
                includeMcp ? "true" : "false",
                expectedTablesCsv(expectedTables),
                agentWiki
        );

        String raw = llmClient.generateText(
                systemPrompt.formatted(
                        expectedTablesCsv(expectedTables),
                        agentWiki
                ) + "\n\n" + userPrompt,
                "{}"
        );
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

        boolean success = warnings.isEmpty();
        return new ExperimentalSqlGenerationResponse(
                success,
                sql,
                warnings,
                "Experimental output: review before applying in production."
        );
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

    private String loadSqlGenerationAgentWiki() {
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(SQL_GENERATION_AGENT_RESOURCE)) {
            if (in == null) {
                return "SQL generation wiki not found at classpath:" + SQL_GENERATION_AGENT_RESOURCE;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "Failed to load SQL generation wiki: " + e.getMessage();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
