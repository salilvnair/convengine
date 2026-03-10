package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticQueryDebugResponse {
    private boolean success;
    private String question;
    private String conversationId;
    private String selectedEntity;
    private String selectedEntityReason;
    private List<Map<String, Object>> candidateEntities = new ArrayList<>();
    private Map<String, Object> retrieval = new LinkedHashMap<>();
    private Map<String, Object> ast = new LinkedHashMap<>();
    private String astVersion;
    private String astRawJson;
    private String compiledSql;
    private Map<String, Object> compiledSqlParams = new LinkedHashMap<>();
    private String summary;
    private Map<String, Object> llmInput = new LinkedHashMap<>();
    private Map<String, Object> llmOutput = new LinkedHashMap<>();
    private Map<String, Object> llmError = new LinkedHashMap<>();
    private Map<String, Object> analysis = new LinkedHashMap<>();
    private String note;
}
