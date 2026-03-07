package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelValidateResponse {
    private boolean valid;
    private List<SemanticModelIssue> errors = new ArrayList<>();
    private List<SemanticModelIssue> warnings = new ArrayList<>();
}

