package com.github.salilvnair.convengine.experimental;

import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationRequest;
import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationResponse;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExperimentalSqlGenerationService {

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?i)\\b(update|delete|drop|alter|truncate|create\\s+table)\\b"
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

        String systemPrompt = """
                You generate SQL INSERT scripts for ConvEngine configuration.
                Return SQL ONLY. No markdown. No explanation.
                
                Rules:
                - Use only INSERT INTO statements.
                - Use only ce_* tables.
                - Never emit UPDATE, DELETE, DROP, ALTER, CREATE TABLE, TRUNCATE.
                - Keep values deterministic and readable.
                - Include minimal but complete rows for intent, classifier, response, prompt templates, and optional rules.
                - Ensure values align to implemented enums:
                  response_type: EXACT | DERIVED
                  output_format: TEXT | JSON
                  rule_type: EXACT | REGEX | JSON_PATH
                  action: SET_INTENT | RESOLVE_INTENT | SET_STATE | SET_JSON | GET_CONTEXT | GET_SCHEMA_EXTRACTED_DATA | GET_SESSION | SET_TASK
                - Prefer uppercase for enum-like fields.
                - Add comments only with SQL line comments (--).
                """;

        String userPrompt = """
                Build ce_* INSERT SQL for this scenario:
                scenario: %s
                domain: %s
                constraints: %s
                include_mcp: %s
                
                Tables to consider:
                ce_intent, ce_intent_classifier, ce_output_schema, ce_prompt_template,
                ce_response, ce_rule, ce_policy, ce_config, ce_mcp_tool, ce_mcp_db_tool.
                """.formatted(
                scenario,
                safe(request.getDomain()),
                safe(request.getConstraints()),
                request.getIncludeMcp() == null || request.getIncludeMcp() ? "true" : "false"
        );

        String raw = llmClient.generateText(systemPrompt + "\n\n" + userPrompt, "{}");
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
        for (String line : sql.split("\\R")) {
            String normalized = line.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("insert into ce_")) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
