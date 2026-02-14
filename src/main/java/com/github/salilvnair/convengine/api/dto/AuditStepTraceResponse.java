package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditStepTraceResponse {
    private String step;
    private String stepClass;
    private String sourceFile;
    private String status;
    private String startedAt;
    private String endedAt;
    private Long durationMs;
    private String error;
    private List<AuditStageTraceResponse> stages;
}
