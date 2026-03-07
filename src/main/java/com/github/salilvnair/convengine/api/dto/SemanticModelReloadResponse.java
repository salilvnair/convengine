package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelReloadResponse {
    private boolean success;
    private String source;
    private Integer semanticModelVersion;
    private Integer entityCount;
    private String message;
}

