package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticEntity(
        String description,
        List<String> synonyms,
        SemanticEntityTables tables,
        Map<String, SemanticField> fields
) {
    public SemanticEntity {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
    }
}
