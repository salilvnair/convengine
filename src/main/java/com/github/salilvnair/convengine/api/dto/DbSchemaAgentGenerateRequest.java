package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DbSchemaAgentGenerateRequest {
    private String schema;
    private String prefix = "ce_";
    private Boolean upsert = true;
    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        private String tableName;
        private String columnName;
        private String role;
        private String description;
        private String tags;
    }
}
