package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class SemanticModelGenerateRequest {
    private String schema;
    private String prefix;
    private String businessHints;
    private String existingYaml;
    private List<Row> rows = new ArrayList<>();
    private Map<String, Object> inspectedSchema = new LinkedHashMap<>();

    @Data
    public static class Row {
        private String tableName;
        private String columnName;
        private String role;
        private String description;
        private String tags;
        private String validValues;
    }
}

