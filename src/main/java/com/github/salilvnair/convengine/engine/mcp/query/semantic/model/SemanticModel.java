package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

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
        Map<String, SemanticMetric> metrics
) {
    public SemanticModel {
        entities = entities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        tables = tables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tables);
        synonyms = synonyms == null ? new LinkedHashMap<>() : new LinkedHashMap<>(synonyms);
        metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
    }
}
