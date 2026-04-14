package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineSqlTableResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.SemanticTableNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelDynamicOverlayService {

    private static final String JOIN_HINT_TABLE = SemanticTableNames.SEMANTIC_JOIN_HINT;
    private static final String VALUE_PATTERN_TABLE = SemanticTableNames.SEMANTIC_VALUE_PATTERN;
    private static final String ENTITY_TABLE = SemanticTableNames.SEMANTIC_ENTITY;
    private static final String RELATIONSHIP_TABLE = SemanticTableNames.SEMANTIC_RELATIONSHIP;
    private static final String MODEL_TABLE = SemanticTableNames.SEMANTIC_MODEL;
    private static final String SETTING_TABLE = SemanticTableNames.SEMANTIC_SETTING;
    private static final String SOURCE_TABLE_TABLE = SemanticTableNames.SEMANTIC_SOURCE_TABLE;
    private static final String SOURCE_COLUMN_TABLE = SemanticTableNames.SEMANTIC_SOURCE_COLUMN;
    private static final String LEXICON_TABLE = SemanticTableNames.SEMANTIC_LEXICON;
    private static final String RULE_ALLOWED_TABLE = SemanticTableNames.SEMANTIC_RULE_ALLOWED_TABLE;
    private static final String RULE_DENY_TABLE = SemanticTableNames.SEMANTIC_RULE_DENY_OPERATION;
    private static final String RULE_CONFIG_TABLE = SemanticTableNames.SEMANTIC_RULE_CONFIG;

    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired(required = false)
    private ConvEngineSqlTableResolver tableResolver;

    public SemanticModel apply(SemanticModel baseModel) {
        SemanticModel source = baseModel == null
                ? new SemanticModel(1, "", "", null, null, null, null, null)
                : baseModel;
        NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return source;
        }

        Map<String, Object> modelRow = fetchModelRow(jdbcTemplate);
        int version = resolveInt(modelRow.get("model_version"), source.version() <= 0 ? 1 : source.version());
        String database = firstNonBlank(normalizeText(modelRow.get("database_name")), source.database());
        String description = firstNonBlank(normalizeText(modelRow.get("description")), source.description());
        SemanticSettings settings = buildSettings(source.settings(), fetchSettingRows(jdbcTemplate));
        Map<String, SemanticTable> tables = buildTables(source.tables(), fetchSourceTableRows(jdbcTemplate), fetchSourceColumnRows(jdbcTemplate));
        Map<String, List<String>> synonyms = buildLexicon(source.synonyms(), fetchLexiconRows(jdbcTemplate));
        SemanticRules rules = buildRules(source.rules(), fetchAllowedRuleTableRows(jdbcTemplate), fetchDenyRuleOperationRows(jdbcTemplate), fetchRuleConfigRows(jdbcTemplate));
        Map<String, SemanticEntity> mergedEntities = mergeEntities(null, fetchEntityRows(jdbcTemplate));
        List<SemanticRelationship> mergedRelationships = mergeRelationships(null, fetchRelationshipRows(jdbcTemplate));
        Map<String, SemanticJoinHint> mergedJoinHints = mergeJoinHints(null, fetchJoinHintRows(jdbcTemplate));
        List<SemanticIntentFieldRemap> mergedValuePatterns = mergeValuePatterns(null, fetchValuePatternRows(jdbcTemplate));

        return new SemanticModel(
                version,
                database,
                description,
                mergedEntities,
                mergedRelationships,
                tables,
                synonyms,
                source.metrics(),
                settings,
                mergedJoinHints,
                rules,
                mergedValuePatterns,
                source.intentRules()
        );
    }

    private Map<String, Object> fetchModelRow(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT model_version, database_name, description, enabled
                FROM ce_semantic_model
                WHERE enabled = true
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(resolveSql(sql), Map.of());
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception ex) {
            log.debug("Skipping semantic model metadata DB load from table={} cause={}", MODEL_TABLE, ex.getMessage());
            return Map.of();
        }
    }

    private List<Map<String, Object>> fetchSettingRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT setting_key, setting_value, priority, enabled
                FROM ce_semantic_setting
                ORDER BY COALESCE(priority, 999999), setting_key
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic settings DB load from table={} cause={}", SETTING_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchSourceTableRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT table_name, description, priority, enabled
                FROM ce_semantic_source_table
                ORDER BY COALESCE(priority, 999999), table_name
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic source-table DB load from table={} cause={}", SOURCE_TABLE_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchSourceColumnRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT table_name, column_name, data_type, is_primary_key, description,
                       foreign_key_table, foreign_key_column, priority, enabled
                FROM ce_semantic_source_column
                ORDER BY COALESCE(priority, 999999), table_name, column_name
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic source-column DB load from table={} cause={}", SOURCE_COLUMN_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchLexiconRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT term_key, synonym_text, priority, enabled
                FROM ce_semantic_lexicon
                ORDER BY COALESCE(priority, 999999), term_key, synonym_text
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic lexicon DB load from table={} cause={}", LEXICON_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchAllowedRuleTableRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT table_name, priority, enabled
                FROM ce_semantic_rule_allowed_table
                ORDER BY COALESCE(priority, 999999), table_name
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic rule allow-list DB load from table={} cause={}", RULE_ALLOWED_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchDenyRuleOperationRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT operation_name, priority, enabled
                FROM ce_semantic_rule_deny_operation
                ORDER BY COALESCE(priority, 999999), operation_name
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic rule deny-ops DB load from table={} cause={}", RULE_DENY_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchRuleConfigRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT max_result_limit, enabled
                FROM ce_semantic_rule_config
                ORDER BY created_at DESC
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic rule config DB load from table={} cause={}", RULE_CONFIG_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchJoinHintRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT base_table, join_table, priority, enabled
                FROM ce_semantic_join_hint
                ORDER BY COALESCE(priority, 999999), base_table, join_table
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic join-hint DB overlay from table={} cause={}", JOIN_HINT_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchValuePatternRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT from_field, to_field, value_starts_with, priority, enabled
                FROM ce_semantic_value_pattern
                ORDER BY COALESCE(priority, 999999), from_field, to_field
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic value-pattern DB overlay from table={} cause={}", VALUE_PATTERN_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchEntityRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String entityTable = resolveTableName(ENTITY_TABLE);
        String sql = "SELECT entity_name, description, primary_table, related_tables, synonyms, fields_json, enabled, priority "
                + "FROM " + entityTable + " ORDER BY COALESCE(priority, 999999), entity_name";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, Map.of());
            if (!rows.isEmpty()) {
                return rows;
            }
            return jdbcTemplate.queryForList(
                    "SELECT entity_name, description, primary_table, related_tables, synonyms, fields_json FROM "
                            + entityTable + " ORDER BY entity_name",
                    Map.of()
            );
        } catch (Exception ex) {
            log.debug("Skipping semantic entity DB load from table={} cause={}", ENTITY_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchRelationshipRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT relationship_name, description, from_table, from_column, to_table, to_column, relation_type, enabled, priority
                FROM ce_semantic_relationship
                ORDER BY COALESCE(priority, 999999), relationship_name
                """;
        try {
            return jdbcTemplate.queryForList(resolveSql(sql), Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic relationship DB load from table={} cause={}", RELATIONSHIP_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private Map<String, SemanticJoinHint> mergeJoinHints(Map<String, SemanticJoinHint> yamlHints, List<Map<String, Object>> dbRows) {
        Map<String, SemanticJoinHint> out = new LinkedHashMap<>();
        if (yamlHints != null) {
            out.putAll(yamlHints);
        }
        if (dbRows == null || dbRows.isEmpty()) {
            return out;
        }

        Map<String, LinkedHashSet<String>> dbHints = new LinkedHashMap<>();
        for (Map<String, Object> row : dbRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String baseTable = normalizeText(row.get("base_table"));
            String joinTable = normalizeText(row.get("join_table"));
            if (baseTable == null || joinTable == null) {
                continue;
            }
            dbHints.computeIfAbsent(baseTable, k -> new LinkedHashSet<>()).add(joinTable);
        }
        if (dbHints.isEmpty()) {
            return out;
        }

        dbHints.forEach((baseTable, joinTables) ->
                out.put(baseTable, new SemanticJoinHint(new ArrayList<>(joinTables))));
        return out;
    }

    private Map<String, SemanticEntity> mergeEntities(Map<String, SemanticEntity> yamlEntities, List<Map<String, Object>> dbRows) {
        Map<String, SemanticEntity> out = new LinkedHashMap<>();
        if (yamlEntities != null) {
            out.putAll(yamlEntities);
        }
        if (dbRows == null || dbRows.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : dbRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String entityName = normalizeText(row.get("entity_name"));
            if (entityName == null) {
                continue;
            }

            SemanticEntity base = out.get(entityName);
            String description = firstNonBlank(normalizeText(row.get("description")), base == null ? null : base.description());
            List<String> synonyms = splitTokensWithFallback(row.get("synonyms"), base == null ? List.of() : base.synonyms());
            SemanticEntityTables tables = resolveEntityTables(row, base);
            Map<String, SemanticField> fields = resolveEntityFields(row, base);

            out.put(entityName, new SemanticEntity(description, synonyms, tables, fields));
        }
        return out;
    }

    private List<SemanticRelationship> mergeRelationships(List<SemanticRelationship> yamlRelationships,
                                                          List<Map<String, Object>> dbRows) {
        Map<String, SemanticRelationship> out = new LinkedHashMap<>();
        if (yamlRelationships != null) {
            for (SemanticRelationship relationship : yamlRelationships) {
                if (relationship == null || relationship.name() == null || relationship.name().isBlank()) {
                    continue;
                }
                out.put(relationship.name(), relationship);
            }
        }

        if (dbRows == null || dbRows.isEmpty()) {
            return new ArrayList<>(out.values());
        }

        for (Map<String, Object> row : dbRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String relationshipName = normalizeText(row.get("relationship_name"));
            String fromTable = normalizeText(row.get("from_table"));
            String fromColumn = normalizeText(row.get("from_column"));
            String toTable = normalizeText(row.get("to_table"));
            String toColumn = normalizeText(row.get("to_column"));
            if (relationshipName == null || fromTable == null || fromColumn == null || toTable == null || toColumn == null) {
                continue;
            }
            SemanticRelationship base = out.get(relationshipName);
            String description = firstNonBlank(normalizeText(row.get("description")), base == null ? null : base.description());
            String relationType = firstNonBlank(normalizeText(row.get("relation_type")), base == null ? null : base.type());
            out.put(relationshipName, new SemanticRelationship(
                    relationshipName,
                    description,
                    new SemanticRelationshipEnd(fromTable, fromColumn),
                    new SemanticRelationshipEnd(toTable, toColumn),
                    relationType
            ));
        }

        return new ArrayList<>(out.values());
    }

    private List<SemanticIntentFieldRemap> mergeValuePatterns(List<SemanticIntentFieldRemap> yamlPatterns,
                                                              List<Map<String, Object>> dbRows) {
        Map<String, SemanticIntentFieldRemap> mergedByPair = new LinkedHashMap<>();
        if (yamlPatterns != null) {
            for (SemanticIntentFieldRemap pattern : yamlPatterns) {
                if (pattern == null || pattern.fromField() == null || pattern.toField() == null) {
                    continue;
                }
                mergedByPair.put(pairKey(pattern.fromField(), pattern.toField()), pattern);
            }
        }

        if (dbRows == null || dbRows.isEmpty()) {
            return new ArrayList<>(mergedByPair.values());
        }

        Map<String, PatternAccumulator> dbPatterns = new LinkedHashMap<>();
        for (Map<String, Object> row : dbRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String fromField = normalizeText(row.get("from_field"));
            String toField = normalizeText(row.get("to_field"));
            if (fromField == null || toField == null) {
                continue;
            }
            String key = pairKey(fromField, toField);
            PatternAccumulator accumulator = dbPatterns.computeIfAbsent(key, k -> new PatternAccumulator(fromField, toField));
            for (String prefix : splitTokens(row.get("value_starts_with"))) {
                accumulator.valueStartsWith.add(prefix);
            }
        }

        if (dbPatterns.isEmpty()) {
            return new ArrayList<>(mergedByPair.values());
        }

        dbPatterns.forEach((key, acc) -> mergedByPair.put(
                key,
                new SemanticIntentFieldRemap(acc.fromField, acc.toField, new ArrayList<>(acc.valueStartsWith))
        ));

        return new ArrayList<>(mergedByPair.values());
    }

    private SemanticSettings buildSettings(SemanticSettings base, List<Map<String, Object>> dbRows) {
        Integer defaultLimit = base == null ? null : base.defaultLimit();
        String timezone = base == null ? null : base.timezone();
        String sqlDialect = base == null ? null : base.sqlDialect();
        for (Map<String, Object> row : dbRows == null ? List.<Map<String, Object>>of() : dbRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String key = normalizeText(row.get("setting_key"));
            String value = normalizeText(row.get("setting_value"));
            if (key == null || value == null) {
                continue;
            }
            switch (key.toLowerCase(Locale.ROOT)) {
                case "default_limit" -> defaultLimit = resolveInt(value, defaultLimit == null ? 100 : defaultLimit);
                case "timezone" -> timezone = value;
                case "sql_dialect" -> sqlDialect = value;
                default -> {
                    // no-op for unknown setting keys
                }
            }
        }
        return new SemanticSettings(defaultLimit, timezone, sqlDialect);
    }

    private Map<String, SemanticTable> buildTables(Map<String, SemanticTable> baseTables,
                                                   List<Map<String, Object>> tableRows,
                                                   List<Map<String, Object>> columnRows) {
        Map<String, SemanticTable> out = new LinkedHashMap<>();
        if (baseTables != null) {
            out.putAll(baseTables);
        }

        for (Map<String, Object> row : tableRows == null ? List.<Map<String, Object>>of() : tableRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String tableName = normalizeText(row.get("table_name"));
            if (tableName == null) {
                continue;
            }
            String description = normalizeText(row.get("description"));
            SemanticTable base = out.get(tableName);
            Map<String, SemanticColumn> columns = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base.columns());
            out.put(tableName, new SemanticTable(firstNonBlank(description, base == null ? null : base.description()), columns));
        }

        for (Map<String, Object> row : columnRows == null ? List.<Map<String, Object>>of() : columnRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String tableName = normalizeText(row.get("table_name"));
            String columnName = normalizeText(row.get("column_name"));
            if (tableName == null || columnName == null) {
                continue;
            }
            SemanticTable table = out.get(tableName);
            if (table == null) {
                table = new SemanticTable(null, new LinkedHashMap<>());
                out.put(tableName, table);
            }
            Map<String, SemanticColumn> columns = new LinkedHashMap<>(table.columns());
            String type = normalizeText(row.get("data_type"));
            String description = normalizeText(row.get("description"));
            Boolean primaryKey = toBoolean(row.get("is_primary_key"));
            String fkTable = normalizeText(row.get("foreign_key_table"));
            String fkColumn = normalizeText(row.get("foreign_key_column"));
            SemanticForeignKey fk = (fkTable == null || fkColumn == null) ? null : new SemanticForeignKey(fkTable, fkColumn);
            columns.put(columnName, new SemanticColumn(type, description, primaryKey, fk));
            out.put(tableName, new SemanticTable(table.description(), columns));
        }

        return out;
    }

    private Map<String, List<String>> buildLexicon(Map<String, List<String>> baseSynonyms,
                                                   List<Map<String, Object>> rows) {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        if (baseSynonyms != null) {
            for (Map.Entry<String, List<String>> entry : baseSynonyms.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                grouped.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>())
                        .addAll(entry.getValue() == null ? List.of() : entry.getValue());
            }
        }
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            String term = normalizeText(row.get("term_key"));
            String synonym = normalizeText(row.get("synonym_text"));
            if (term == null || synonym == null) {
                continue;
            }
            grouped.computeIfAbsent(term, ignored -> new LinkedHashSet<>()).add(synonym);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        grouped.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    private SemanticRules buildRules(SemanticRules base,
                                     List<Map<String, Object>> allowedTableRows,
                                     List<Map<String, Object>> denyRows,
                                     List<Map<String, Object>> configRows) {
        List<String> allowedTables = base == null ? new ArrayList<>() : new ArrayList<>(base.allowedTables());
        List<String> denyOperations = base == null ? new ArrayList<>() : new ArrayList<>(base.denyOperations());
        Integer maxResultLimit = base == null ? null : base.maxResultLimit();

        if (allowedTableRows != null && !allowedTableRows.isEmpty()) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (Map<String, Object> row : allowedTableRows) {
                if (!isEnabled(row.get("enabled"))) {
                    continue;
                }
                String table = normalizeText(row.get("table_name"));
                if (table != null) {
                    out.add(table);
                }
            }
            if (!out.isEmpty()) {
                allowedTables = new ArrayList<>(out);
            }
        }

        if (denyRows != null && !denyRows.isEmpty()) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (Map<String, Object> row : denyRows) {
                if (!isEnabled(row.get("enabled"))) {
                    continue;
                }
                String op = normalizeText(row.get("operation_name"));
                if (op != null) {
                    out.add(op);
                }
            }
            if (!out.isEmpty()) {
                denyOperations = new ArrayList<>(out);
            }
        }

        for (Map<String, Object> row : configRows == null ? List.<Map<String, Object>>of() : configRows) {
            if (!isEnabled(row.get("enabled"))) {
                continue;
            }
            Integer limit = resolveInt(row.get("max_result_limit"), null);
            if (limit != null) {
                maxResultLimit = limit;
                break;
            }
        }

        return new SemanticRules(allowedTables, denyOperations, maxResultLimit);
    }

    private boolean isEnabled(Object enabled) {
        if (enabled == null) {
            return true;
        }
        if (enabled instanceof Boolean b) {
            return b;
        }
        if (enabled instanceof Number n) {
            return n.intValue() != 0;
        }
        String text = String.valueOf(enabled).trim().toLowerCase(Locale.ROOT);
        return !"false".equals(text) && !"0".equals(text) && !"n".equals(text) && !"no".equals(text);
    }

    private Integer resolveInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
            return false;
        }
        return null;
    }

    private List<String> splitTokens(Object value) {
        if (value == null) {
            return List.of();
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,|]");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String token = normalizeText(part);
            if (token != null) {
                out.add(token);
            }
        }
        return out;
    }

    private List<String> splitTokensWithFallback(Object value, List<String> fallback) {
        List<String> tokens = splitTokens(value);
        return tokens.isEmpty() ? fallback : tokens;
    }

    private SemanticEntityTables resolveEntityTables(Map<String, Object> row, SemanticEntity base) {
        String primary = normalizeText(row.get("primary_table"));
        List<String> related = splitTokens(row.get("related_tables"));
        if (primary == null && base != null && base.tables() != null) {
            primary = base.tables().primary();
        }
        if (related.isEmpty() && base != null && base.tables() != null) {
            related = base.tables().related();
        }
        return new SemanticEntityTables(primary, related);
    }

    private Map<String, SemanticField> resolveEntityFields(Map<String, Object> row, SemanticEntity base) {
        Map<String, SemanticField> fields = new LinkedHashMap<>();
        if (base != null && base.fields() != null) {
            fields.putAll(base.fields());
        }
        String fieldsJson = normalizeText(row.get("fields_json"));
        if (fieldsJson == null) {
            return fields;
        }
        try {
            Map<String, SemanticField> parsed = mapper.readValue(fieldsJson, new TypeReference<Map<String, SemanticField>>() {
            });
            if (parsed != null && !parsed.isEmpty()) {
                fields.putAll(parsed);
            }
        } catch (Exception ex) {
            log.debug("Ignoring invalid fields_json in semantic entity override row. cause={}", ex.getMessage());
        }
        return fields;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String resolveSql(String sql) {
        return tableResolver == null ? sql : tableResolver.resolveSql(sql);
    }

    private String resolveTableName(String logicalName) {
        return tableResolver == null ? logicalName : tableResolver.resolveTableName(logicalName);
    }

    private String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String pairKey(String fromField, String toField) {
        return Objects.toString(fromField, "") + "->" + Objects.toString(toField, "");
    }

    private static final class PatternAccumulator {
        private final String fromField;
        private final String toField;
        private final LinkedHashSet<String> valueStartsWith = new LinkedHashSet<>();

        private PatternAccumulator(String fromField, String toField) {
            this.fromField = fromField;
            this.toField = toField;
        }
    }
}
