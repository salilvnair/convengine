package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelGenerateResponse {
    private boolean success;
    private String yaml;
    private List<SemanticModelIssue> diagnostics = new ArrayList<>();
    private String note;
}

