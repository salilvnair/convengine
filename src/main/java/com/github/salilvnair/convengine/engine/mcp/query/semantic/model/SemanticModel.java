package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticModel(
        int version,
        String database,
        String description,
        Map<String, SemanticEntity> entities,
        List<SemanticRelationship> relationships,
        Map<String, SemanticTable> tables,
        Map<String, List<String>> synonyms,
        Map<String, SemanticMetric> metrics,
        SemanticSettings settings,
        @JsonProperty("join_hints") Map<String, SemanticJoinHint> joinHints,
        SemanticRules rules,
        @JsonProperty("value_patterns") List<SemanticIntentFieldRemap> valuePatterns,
        @JsonProperty("intent_rules") Map<String, SemanticIntentRule> intentRules
) {
    public SemanticModel {
        entities = entities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        tables = tables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tables);
        synonyms = synonyms == null ? new LinkedHashMap<>() : new LinkedHashMap<>(synonyms);
        metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
        joinHints = joinHints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(joinHints);
        valuePatterns = valuePatterns == null ? List.of() : List.copyOf(valuePatterns);
        intentRules = intentRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(intentRules);
    }

    public SemanticModel(
            int version,
            String database,
            String description,
            Map<String, SemanticEntity> entities,
            List<SemanticRelationship> relationships,
            Map<String, SemanticTable> tables,
            Map<String, List<String>> synonyms,
            Map<String, SemanticMetric> metrics,
            Map<String, SemanticIntentRule> intentRules
    ) {
        this(version, database, description, entities, relationships, tables, synonyms, metrics, null, null, null, null, intentRules);
    }

    public SemanticModel(
            int version,
            String database,
            String description,
            Map<String, SemanticEntity> entities,
            List<SemanticRelationship> relationships,
            Map<String, SemanticTable> tables,
            Map<String, List<String>> synonyms,
            Map<String, SemanticMetric> metrics
    ) {
        this(version, database, description, entities, relationships, tables, synonyms, metrics, null, null, null, null, null);
    }
}
