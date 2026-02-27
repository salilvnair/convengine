package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerboseStreamPayload {
    private Long verboseId;
    private String eventType;
    private String stepName;
    private String determinant;
    private String intent;
    private String state;
    private Long ruleId;
    private String toolCode;
    private String level;
    private String text;
    private String message;
    private String errorMessage;
    private Map<String, Object> metadata;
}
