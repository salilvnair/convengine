package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final String JOIN_HINT_TABLE = "ce_semantic_join_hint";
    private static final String VALUE_PATTERN_TABLE = "ce_semantic_value_pattern";
    private static final String ENTITY_OVERRIDE_TABLE = "ce_semantic_entity_override";
    private static final String RELATIONSHIP_OVERRIDE_TABLE = "ce_semantic_relationship_override";

    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticModel apply(SemanticModel baseModel) {
        if (baseModel == null) {
            return null;
        }
        NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return baseModel;
        }

        Map<String, SemanticEntity> mergedEntities = mergeEntities(baseModel.entities(), fetchEntityOverrideRows(jdbcTemplate));
        List<SemanticRelationship> mergedRelationships = mergeRelationships(baseModel.relationships(), fetchRelationshipOverrideRows(jdbcTemplate));
        Map<String, SemanticJoinHint> mergedJoinHints = mergeJoinHints(baseModel.joinHints(), fetchJoinHintRows(jdbcTemplate));
        List<SemanticIntentFieldRemap> mergedValuePatterns = mergeValuePatterns(baseModel.valuePatterns(), fetchValuePatternRows(jdbcTemplate));

        return new SemanticModel(
                baseModel.version(),
                baseModel.database(),
                baseModel.description(),
                mergedEntities,
                mergedRelationships,
                baseModel.tables(),
                baseModel.synonyms(),
                baseModel.metrics(),
                baseModel.settings(),
                mergedJoinHints,
                baseModel.rules(),
                mergedValuePatterns,
                baseModel.intentRules()
        );
    }

    private List<Map<String, Object>> fetchJoinHintRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT base_table, join_table, priority, enabled
                FROM ce_semantic_join_hint
                ORDER BY COALESCE(priority, 999999), base_table, join_table
                """;
        try {
            return jdbcTemplate.queryForList(sql, Map.of());
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
            return jdbcTemplate.queryForList(sql, Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic value-pattern DB overlay from table={} cause={}", VALUE_PATTERN_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchEntityOverrideRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT entity_name, description, primary_table, related_tables, synonyms, fields_json, enabled, priority
                FROM ce_semantic_entity_override
                ORDER BY COALESCE(priority, 999999), entity_name
                """;
        try {
            return jdbcTemplate.queryForList(sql, Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic entity DB overlay from table={} cause={}", ENTITY_OVERRIDE_TABLE, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchRelationshipOverrideRows(NamedParameterJdbcTemplate jdbcTemplate) {
        String sql = """
                SELECT relationship_name, description, from_table, from_column, to_table, to_column, relation_type, enabled, priority
                FROM ce_semantic_relationship_override
                ORDER BY COALESCE(priority, 999999), relationship_name
                """;
        try {
            return jdbcTemplate.queryForList(sql, Map.of());
        } catch (Exception ex) {
            log.debug("Skipping semantic relationship DB overlay from table={} cause={}", RELATIONSHIP_OVERRIDE_TABLE, ex.getMessage());
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
