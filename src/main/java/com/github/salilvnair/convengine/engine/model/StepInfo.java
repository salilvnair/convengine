package com.github.salilvnair.convengine.engine.model;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class StepInfo {
    private String stepName;
    private String stepClass;
    private String determinant;
    private String status;
    private Long startedAtNs;
    private Long endedAtNs;
    private Long durationMs;
    private String outcome;
    private String errorType;
    private String errorMessage;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, Object> data = new LinkedHashMap<>();
}
