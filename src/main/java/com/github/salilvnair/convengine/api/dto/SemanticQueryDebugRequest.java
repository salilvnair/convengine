package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class SemanticQueryDebugRequest {
    private String question;
    private Boolean includeRetrieval;
    private Boolean includeJsonPath;
    private Boolean includeAst;
    private Boolean includeSqlGeneration;
    private Boolean includeSqlExecution;

    public boolean includeRetrieval() {
        return includeRetrieval == null || includeRetrieval;
    }

    public boolean includeJsonPath() {
        return includeJsonPath == null || includeJsonPath;
    }

    public boolean includeAst() {
        return includeAst == null || includeAst;
    }

    public boolean includeSqlGeneration() {
        return includeSqlGeneration == null || includeSqlGeneration;
    }

    public boolean includeSqlExecution() {
        return includeSqlExecution == null || includeSqlExecution;
    }
}
