package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditTraceResponse {
    private UUID conversationId;
    private List<AuditStepTraceResponse> steps;
    private List<AuditStageTraceResponse> stages;
}
