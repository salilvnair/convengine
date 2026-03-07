package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelIssue {
    private String severity; // ERROR | WARNING
    private String message;
    private Integer line;
    private Integer column;
}

