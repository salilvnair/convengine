package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class ExperimentalSqlGenerationRequest {
    private String scenario;
    private String domain;
    private String constraints;
    private Boolean includeMcp = true;
}
