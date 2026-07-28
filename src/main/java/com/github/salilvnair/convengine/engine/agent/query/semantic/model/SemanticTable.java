package com.github.salilvnair.convengine.engine.agent.query.semantic.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record SemanticTable(
        String description,
        Map<String, SemanticColumn> columns
) {
    public SemanticTable {
        columns = columns == null ? new LinkedHashMap<>() : new LinkedHashMap<>(columns);
    }
}
