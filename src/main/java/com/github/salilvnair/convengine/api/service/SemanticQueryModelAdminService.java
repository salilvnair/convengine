package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.SemanticModelGenerateRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelGenerateResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelIssue;
import com.github.salilvnair.convengine.api.dto.SemanticModelReloadRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelReloadResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelSaveRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelSaveResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelStudioConfigResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelValidateRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelValidateResponse;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelValidator;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticQueryModelAdminService {

    private static final String MODEL_CONFIG_TYPE = "SemanticQueryModel";
    private static final String MODEL_CONFIG_KEY_PREFIX = "MODEL_YAML_";
    private static final String SYSTEM_PROMPT_CONFIG_KEY = "SYSTEM_PROMPT";
    private static final String USER_PROMPT_CONFIG_KEY = "USER_PROMPT";
    private static final int PROMPT_MAX_TABLES = 16;
    private static final int PROMPT_MAX_FOCUS_TABLES = 8;
    private static final int PROMPT_MAX_COLUMNS = 120;
    private static final int PROMPT_MAX_JOINS = 40;
    private static final Pattern YAML_KEY_PATTERN = Pattern.compile("^\\s*([A-Za-z0-9_-]+):");
    private static final Pattern TABLE_COLUMN_REF_PATTERN = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final List<String> EDITABLE_MODEL_SECTIONS = List.of(
            "version", "database", "description", "settings",
            "entities", "tables", "relationships", "synonyms", "rules"
    );
    private static final List<String> DB_MANAGED_MODEL_SECTIONS = List.of(
            "metrics", "intent_rules", "join_hints", "value_patterns"
    );
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are generating semantic-query model YAML for ConvEngine.
            Return ONLY YAML text. No markdown.

            Output requirements:
            - include version, database, settings, entities, tables, relationships, synonyms, rules.
            - exclude metrics, intent_rules, join_hints, value_patterns (they are DB-managed).
            - fields must use semantic names with table.column mapping.
            - include synonyms and example_questions where possible.
            - do not invent table/column names outside provided schema.
            - keep YAML valid and loadable.
            """;
    private static final String DEFAULT_USER_PROMPT = """
            business_hints:
            {{business_hints}}

            existing_yaml:
            {{existing_yaml}}

            inspected_schema:
            {{inspected_schema}}

            edited_rows:
            {{edited_rows}}

            database:
            {{schema}}

            prefix:
            {{prefix}}
            """;

    private final LlmClient llmClient;
    private final SemanticModelValidator semanticModelValidator;
    private final SemanticModelRegistry semanticModelRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;

    private String systemPromptTemplate;
    private String userPromptTemplate;

    @PostConstruct
    public void init() {
        this.systemPromptTemplate = configResolver.resolveString(this, SYSTEM_PROMPT_CONFIG_KEY, DEFAULT_SYSTEM_PROMPT);
        this.userPromptTemplate = configResolver.resolveString(this, USER_PROMPT_CONFIG_KEY, DEFAULT_USER_PROMPT);
    }

    public SemanticModelGenerateResponse generateDraft(SemanticModelGenerateRequest request) {
        SemanticModelGenerateRequest safeRequest = request == null ? new SemanticModelGenerateRequest() : request;
        String llmYaml = "";
        List<SemanticModelIssue> diagnostics = new ArrayList<>();
        String fallback = deterministicDraft(safeRequest);
        PromptTemplateContext promptContext = buildPromptContext(safeRequest);
        String systemPrompt = promptTemplateRenderer.render(systemPromptTemplate, promptContext);
        String userPrompt = promptTemplateRenderer.render(userPromptTemplate, promptContext);

        LlmInvocationContext.set(UUID.randomUUID(), "SEMANTIC_MODEL", "GENERATE_DRAFT");
        try {
            llmYaml = normalizeModelText(llmClient.generateText(null, systemPrompt + "\n\n" + userPrompt, "{}"));
        } catch (Exception ex) {
            diagnostics.add(new SemanticModelIssue("WARNING", "LLM generation failed, using deterministic draft fallback.", null, null));
            log.warn("Semantic model draft generation failed; using fallback. cause={}", ex.getMessage());
        } finally {
            LlmInvocationContext.clear();
        }

        if (llmYaml.isBlank()) {
            llmYaml = fallback;
        }

        SemanticModelValidateRequest validateRequest = new SemanticModelValidateRequest();
        validateRequest.setYaml(llmYaml);
        SemanticModelValidateResponse validate = validate(validateRequest);
        diagnostics.addAll(validate.getErrors());
        diagnostics.addAll(validate.getWarnings());

        SemanticModelGenerateResponse response = new SemanticModelGenerateResponse();
        response.setSuccess(true);
        response.setYaml(llmYaml);
        response.setDiagnostics(diagnostics);
        response.setNote(llmYaml.equals(fallback) ? "Generated deterministic draft." : "Generated with LLM.");
        response.setEditableSections(EDITABLE_MODEL_SECTIONS);
        response.setDbManagedSections(DB_MANAGED_MODEL_SECTIONS);
        return response;
    }

    private PromptTemplateContext buildPromptContext(SemanticModelGenerateRequest request) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("business_hints", safe(request.getBusinessHints()));
        extra.put("existing_yaml", safe(request.getExistingYaml()));
        extra.put("inspected_schema", toJson(compactInspectedSchema(request)));
        extra.put("edited_rows", toJson(compactEditedRows(request.getRows())));
        extra.put("schema", safe(request.getSchema()));
        extra.put("prefix", safe(request.getPrefix()));

        return PromptTemplateContext.builder()
                .templateName("SemanticQueryModelAdminService")
                .systemPrompt(systemPromptTemplate)
                .userPrompt(userPromptTemplate)
                .extra(extra)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compactInspectedSchema(SemanticModelGenerateRequest request) {
        Map<String, Object> source = request == null || request.getInspectedSchema() == null
                ? Map.of()
                : request.getInspectedSchema();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", safe(request == null ? null : request.getSchema()));
        out.put("prefix", safe(request == null ? null : request.getPrefix()));
        out.put("tableCount", source.getOrDefault("tableCount", 0));

        Set<String> focusTables = new LinkedHashSet<>();
        Map<String, Set<String>> focusColumnsByTable = new LinkedHashMap<>();
        if (request != null && request.getRows() != null) {
            for (SemanticModelGenerateRequest.Row row : request.getRows()) {
                if (row == null) continue;
                String table = safe(row.getTableName());
                if (!table.isBlank()) {
                    focusTables.add(table);
                }
                String column = safe(row.getColumnName());
                if (!table.isBlank() && !column.isBlank()) {
                    focusColumnsByTable.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
                }
            }
        }

        List<Map<String, Object>> tables = listOfMaps(source.get("tables"));
        List<Map<String, Object>> columns = listOfMaps(source.get("columns"));
        List<Map<String, Object>> joins = listOfMaps(source.get("joins"));

        Set<String> selectedTables = new LinkedHashSet<>();
        boolean focusMode = !focusTables.isEmpty();
        if (focusMode) {
            selectedTables.addAll(focusTables);
            for (Map<String, Object> j : joins) {
                if (selectedTables.size() >= PROMPT_MAX_FOCUS_TABLES) break;
                String sourceTable = safe(stringValue(j.get("source_table")));
                String targetTable = safe(stringValue(j.get("target_table")));
                if (selectedTables.contains(sourceTable) && !targetTable.isBlank()) {
                    selectedTables.add(targetTable);
                } else if (selectedTables.contains(targetTable) && !sourceTable.isBlank()) {
                    selectedTables.add(sourceTable);
                }
            }
        }
        for (Map<String, Object> t : tables) {
            int tableCap = focusMode ? PROMPT_MAX_FOCUS_TABLES : PROMPT_MAX_TABLES;
            if (selectedTables.size() >= tableCap) break;
            String tableName = safe(stringValue(t.get("table_name")));
            if (!tableName.isBlank()) {
                selectedTables.add(tableName);
            }
        }

        List<Map<String, Object>> compactTables = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            String tableName = safe(stringValue(t.get("table_name")));
            if (selectedTables.contains(tableName)) {
                compactTables.add(Map.of("table_name", tableName));
            }
        }
        out.put("tables", compactTables);

        List<Map<String, Object>> compactColumns = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            if (compactColumns.size() >= PROMPT_MAX_COLUMNS) break;
            String tableName = safe(stringValue(c.get("table_name")));
            if (!selectedTables.contains(tableName)) continue;
            String columnName = safe(stringValue(c.get("column_name")));
            boolean isPk = Boolean.TRUE.equals(c.get("is_primary_key"));
            boolean isFk = Boolean.TRUE.equals(c.get("is_foreign_key"));
            if (focusMode && focusColumnsByTable.containsKey(tableName)
                    && !focusColumnsByTable.get(tableName).contains(columnName)
                    && !isPk && !isFk) {
                continue;
            }
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("table_name", tableName);
            col.put("column_name", columnName);
            col.put("data_type", safe(stringValue(c.get("data_type"))));
            col.put("is_primary_key", isPk);
            col.put("is_foreign_key", isFk);
            compactColumns.add(col);
        }
        out.put("columns", compactColumns);

        List<Map<String, Object>> compactJoins = new ArrayList<>();
        for (Map<String, Object> j : joins) {
            if (compactJoins.size() >= PROMPT_MAX_JOINS) break;
            String sourceTable = safe(stringValue(j.get("source_table")));
            String targetTable = safe(stringValue(j.get("target_table")));
            if (!selectedTables.contains(sourceTable) && !selectedTables.contains(targetTable)) {
                continue;
            }
            Map<String, Object> join = new LinkedHashMap<>();
            join.put("source_table", sourceTable);
            join.put("source_column", safe(stringValue(j.get("source_column"))));
            join.put("target_table", targetTable);
            join.put("target_column", safe(stringValue(j.get("target_column"))));
            compactJoins.add(join);
        }
        out.put("joins", compactJoins);
        out.put("includedCounts", Map.of(
                "tables", compactTables.size(),
                "columns", compactColumns.size(),
                "joins", compactJoins.size()
        ));
        return out;
    }

    private List<Map<String, Object>> compactEditedRows(List<SemanticModelGenerateRequest.Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SemanticModelGenerateRequest.Row row : rows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableName", safe(row.getTableName()));
            item.put("columnName", safe(row.getColumnName()));
            item.put("role", safe(row.getRole()));
            item.put("description", clip(row.getDescription(), 200));
            item.put("tags", clip(row.getTags(), 150));
            item.put("validValues", clip(row.getValidValues(), 150));
            out.add(item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clip(String value, int max) {
        String safe = safe(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    public SemanticModelValidateResponse validate(SemanticModelValidateRequest request) {
        List<SemanticModelIssue> errors = new ArrayList<>();
        List<SemanticModelIssue> warnings = new ArrayList<>();
        String yaml = safe(request == null ? null : request.getYaml());
        if (yaml.isBlank()) {
            errors.add(new SemanticModelIssue("ERROR", "YAML is required.", 1, 1));
            return new SemanticModelValidateResponse(false, errors, warnings);
        }

        SemanticModel model;
        try {
            model = mapper().readValue(yaml, SemanticModel.class);
        } catch (Exception ex) {
            errors.add(new SemanticModelIssue("ERROR", "YAML parse failed: " + ex.getMessage(), 1, 1));
            return new SemanticModelValidateResponse(false, errors, warnings);
        }

        for (String message : semanticModelValidator.validate(model)) {
            errors.add(new SemanticModelIssue("ERROR", message, resolveLine(yaml, message), null));
        }

        if (model.entities() != null) {
            model.entities().forEach((entityName, entity) -> {
                if (entity == null || entity.fields() == null) {
                    return;
                }
                Set<String> aliasSeen = new LinkedHashSet<>();
                entity.fields().forEach((fieldName, field) -> {
                    if (field == null || field.column() == null || field.column().isBlank()) {
                        errors.add(new SemanticModelIssue("ERROR",
                                "entity " + entityName + " field " + fieldName + " missing column mapping",
                                resolveLine(yaml, fieldName + ":"), null));
                        return;
                    }
                    Matcher matcher = TABLE_COLUMN_REF_PATTERN.matcher(field.column());
                    if (!matcher.matches()) {
                        errors.add(new SemanticModelIssue("ERROR",
                                "entity " + entityName + " field " + fieldName + " has invalid column format: " + field.column(),
                                resolveLine(yaml, field.column()), null));
                    }
                    if (field.aliases() != null) {
                        for (String alias : field.aliases()) {
                            String normalizedAlias = normalize(alias);
                            if (!normalizedAlias.isBlank() && !aliasSeen.add(normalizedAlias)) {
                                warnings.add(new SemanticModelIssue("WARNING",
                                        "duplicate alias in entity " + entityName + ": " + alias,
                                        resolveLine(yaml, alias), null));
                            }
                        }
                    }
                });
            });
        }

        if (model.metrics() != null && !model.metrics().isEmpty()) {
            warnings.add(new SemanticModelIssue("WARNING",
                    "metrics is DB-managed in semantic v2; keep it empty in semantic-layer.yml.",
                    resolveLine(yaml, "metrics:"), null));
        }
        if (model.intentRules() != null && !model.intentRules().isEmpty()) {
            warnings.add(new SemanticModelIssue("WARNING",
                    "intent_rules is DB-managed in semantic v2; keep it empty in semantic-layer.yml.",
                    resolveLine(yaml, "intent_rules:"), null));
        }
        if (model.joinHints() != null && !model.joinHints().isEmpty()) {
            warnings.add(new SemanticModelIssue("WARNING",
                    "join_hints is DB-managed in semantic v2; keep it empty in semantic-layer.yml.",
                    resolveLine(yaml, "join_hints:"), null));
        }
        if (model.valuePatterns() != null && !model.valuePatterns().isEmpty()) {
            warnings.add(new SemanticModelIssue("WARNING",
                    "value_patterns is DB-managed in semantic v2; keep it empty in semantic-layer.yml.",
                    resolveLine(yaml, "value_patterns:"), null));
        }

        return new SemanticModelValidateResponse(errors.isEmpty(), errors, warnings);
    }

    public SemanticModelSaveResponse save(SemanticModelSaveRequest request) {
        String yaml = safe(request == null ? null : request.getYaml());
        String name = normalizeName(request == null ? null : request.getName());
        Integer version = request == null || request.getVersion() == null ? 1 : request.getVersion();
        if (yaml.isBlank()) {
            return new SemanticModelSaveResponse(false, name, version, "none", "YAML is required.");
        }
        SemanticModelValidateRequest validateRequest = new SemanticModelValidateRequest();
        validateRequest.setYaml(yaml);
        SemanticModelValidateResponse validate = validate(validateRequest);
        if (!validate.isValid()) {
            return new SemanticModelSaveResponse(false, name, version, "none", "Validation failed. Fix errors before save.");
        }

        String configKey = MODEL_CONFIG_KEY_PREFIX + name.toUpperCase(Locale.ROOT);
        Integer existingId = jdbcTemplate.query(
                "SELECT config_id FROM ce_config WHERE config_type = ? AND config_key = ? ORDER BY config_id LIMIT 1",
                ps -> {
                    ps.setString(1, MODEL_CONFIG_TYPE);
                    ps.setString(2, configKey);
                },
                rs -> rs.next() ? rs.getInt(1) : null
        );

        if (existingId != null) {
            jdbcTemplate.update(
                    "UPDATE ce_config SET config_value = ?, enabled = ?, created_at = ? WHERE config_id = ?",
                    yaml, true, OffsetDateTime.now(), existingId
            );
        } else {
            Integer nextId = jdbcTemplate.query(
                    "SELECT COALESCE(MAX(config_id), 0) + 1 FROM ce_config",
                    rs -> rs.next() ? rs.getInt(1) : 1
            );
            jdbcTemplate.update(
                    "INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at) VALUES(?,?,?,?,?,?)",
                    nextId, MODEL_CONFIG_TYPE, configKey, yaml, true, OffsetDateTime.now()
            );
        }
        return new SemanticModelSaveResponse(true, name, version, "ce_config", "Saved semantic model YAML.");
    }

    public SemanticModelReloadResponse reload(SemanticModelReloadRequest request) {
        try {
            String yaml = safe(request == null ? null : request.getYaml());
            String source;
            if (!yaml.isBlank()) {
                SemanticModel model = mapper().readValue(yaml, SemanticModel.class);
                semanticModelRegistry.setModel(model);
                source = "request";
            } else if (request != null && Boolean.TRUE.equals(request.getUseSavedModel())) {
                String name = normalizeName(request.getName());
                String configKey = MODEL_CONFIG_KEY_PREFIX + name.toUpperCase(Locale.ROOT);
                String stored = jdbcTemplate.query(
                        "SELECT config_value FROM ce_config WHERE config_type = ? AND config_key = ? AND enabled = true ORDER BY config_id LIMIT 1",
                        ps -> {
                            ps.setString(1, MODEL_CONFIG_TYPE);
                            ps.setString(2, configKey);
                        },
                        rs -> rs.next() ? rs.getString(1) : null
                );
                if (stored == null || stored.isBlank()) {
                    return new SemanticModelReloadResponse(false, "ce_config", null, null, "No saved model found for name=" + name);
                }
                SemanticModel model = mapper().readValue(stored, SemanticModel.class);
                semanticModelRegistry.setModel(model);
                source = "ce_config";
            } else {
                semanticModelRegistry.refresh();
                source = "model-path";
            }
            SemanticModel active = semanticModelRegistry.getModel();
            return new SemanticModelReloadResponse(
                    true,
                    source,
                    active == null ? null : active.version(),
                    active == null || active.entities() == null ? 0 : active.entities().size(),
                    "Semantic model reloaded."
            );
        } catch (Exception ex) {
            return new SemanticModelReloadResponse(false, "error", null, null, "Reload failed: " + ex.getMessage());
        }
    }

    public String currentModelYaml() {
        try {
            SemanticModel model = semanticModelRegistry.getModel();
            if (model == null) {
                return "";
            }
            return mapper().writeValueAsString(model);
        } catch (Exception ex) {
            log.warn("Failed to render current semantic model as yaml. cause={}", ex.getMessage());
            return "";
        }
    }

    public SemanticModelStudioConfigResponse studioConfig() {
        return new SemanticModelStudioConfigResponse(
                true,
                EDITABLE_MODEL_SECTIONS,
                DB_MANAGED_MODEL_SECTIONS,
                "Builder palette should exclude DB-managed sections."
        );
    }

    private String deterministicDraft(SemanticModelGenerateRequest request) {
        String database = safe(request.getSchema());
        if (database.isBlank()) {
            database = "demo_ops";
        }
        Map<String, Set<String>> tableColumns = new LinkedHashMap<>();
        if (request.getRows() != null) {
            request.getRows().forEach(r -> {
                if (r == null) return;
                String table = safe(r.getTableName());
                String column = safe(r.getColumnName());
                if (table.isBlank() || column.isBlank()) return;
                tableColumns.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
            });
        }
        if (request.getInspectedSchema() != null && request.getInspectedSchema().get("columns") instanceof List<?> cols) {
            for (Object o : cols) {
                if (!(o instanceof Map<?, ?> c)) continue;
                String table = safe(obj(c.get("table_name")));
                String column = safe(obj(c.get("column_name")));
                if (table.isBlank() || column.isBlank()) continue;
                tableColumns.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
            }
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("database: ").append(database).append("\n");
        yaml.append("description: Generated semantic model draft\n");
        yaml.append("settings:\n");
        yaml.append("  default_limit: 100\n");
        yaml.append("  timezone: UTC\n");
        yaml.append("  sql_dialect: postgres\n");
        yaml.append("\nentities:\n");
        for (Map.Entry<String, Set<String>> e : tableColumns.entrySet()) {
            String table = e.getKey();
            String entity = toPascal(table.replaceFirst("^(ce_)", ""));
            yaml.append("  ").append(entity).append(":\n");
            yaml.append("    description: Auto-generated from ").append(table).append("\n");
            yaml.append("    synonyms: []\n");
            yaml.append("    tables:\n");
            yaml.append("      primary: ").append(table).append("\n");
            yaml.append("      related: []\n");
            yaml.append("    fields:\n");
            for (String col : e.getValue()) {
                yaml.append("      ").append(toCamel(col)).append(":\n");
                yaml.append("        column: ").append(table).append(".").append(col).append("\n");
                yaml.append("        type: string\n");
                yaml.append("        searchable: true\n");
                yaml.append("        filterable: true\n");
            }
        }
        yaml.append("\ntables:\n");
        for (Map.Entry<String, Set<String>> e : tableColumns.entrySet()) {
            yaml.append("  ").append(e.getKey()).append(":\n");
            yaml.append("    description: Auto-generated table entry\n");
            yaml.append("    columns:\n");
            for (String col : e.getValue()) {
                yaml.append("      ").append(col).append(":\n");
                yaml.append("        type: varchar\n");
            }
        }
        yaml.append("\nrelationships: []\n");
        yaml.append("synonyms: {}\n");
        yaml.append("rules:\n");
        yaml.append("  allowed_tables:\n");
        for (String table : tableColumns.keySet()) {
            yaml.append("    - ").append(table).append("\n");
        }
        yaml.append("  deny_operations: [DELETE, UPDATE, DROP]\n");
        yaml.append("  max_result_limit: 500\n");
        return yaml.toString();
    }

    private String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Integer resolveLine(String yaml, String hint) {
        String safeHint = safe(hint);
        if (safeHint.isBlank()) {
            return null;
        }
        String[] lines = safe(yaml).split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(safeHint)) {
                return i + 1;
            }
        }
        String reduced = extractKeyFromMessage(safeHint);
        if (reduced != null && !reduced.isBlank()) {
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(reduced)) {
                    return i + 1;
                }
            }
        }
        return null;
    }

    private String extractKeyFromMessage(String message) {
        Matcher matcher = YAML_KEY_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeModelText(String raw) {
        String text = safe(raw);
        if (text.startsWith("```")) {
            text = text.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\n", "");
            text = text.replaceAll("(?s)\\n```$", "");
        }
        return text.trim();
    }

    private ObjectMapper mapper() {
        try {
            Class<?> yamlFactoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
            Class<?> jsonFactoryClass = Class.forName("com.fasterxml.jackson.core.JsonFactory");
            Object yamlFactory = yamlFactoryClass.getDeclaredConstructor().newInstance();
            
            try {
                Class<?> featureClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLGenerator$Feature");
                java.lang.reflect.Method disableMethod = yamlFactoryClass.getMethod("disable", featureClass);
                java.lang.reflect.Method enableMethod = yamlFactoryClass.getMethod("enable", featureClass);
                for (Object enumConstant : featureClass.getEnumConstants()) {
                    String name = ((Enum<?>) enumConstant).name();
                    if ("SPLIT_LINES".equals(name)) {
                        disableMethod.invoke(yamlFactory, enumConstant);
                    } else if ("MINIMIZE_QUOTES".equals(name) || "LITERAL_BLOCK_STYLE".equals(name)) {
                        enableMethod.invoke(yamlFactory, enumConstant);
                    }
                }
            } catch (Throwable ignored) {
                // Ignore feature configuration if unavailable
            }

            ObjectMapper mapper = (ObjectMapper) ObjectMapper.class
                    .getConstructor(jsonFactoryClass)
                    .newInstance(yamlFactory);
            return mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        } catch (Throwable ignored) {
            return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
    }

    private String normalizeName(String name) {
        String n = safe(name);
        if (n.isBlank()) {
            return "default";
        }
        return n.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String obj(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String toCamel(String snake) {
        String s = safe(snake).toLowerCase(Locale.ROOT);
        if (s.isBlank()) return s;
        String[] parts = s.split("_+");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) continue;
            out.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
        }
        return out.toString();
    }

    private String toPascal(String text) {
        String s = safe(text).toLowerCase(Locale.ROOT);
        if (s.isBlank()) return s;
        String[] parts = s.split("_+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            out.append(p.substring(0, 1).toUpperCase(Locale.ROOT)).append(p.substring(1));
        }
        return out.toString();
    }
}
