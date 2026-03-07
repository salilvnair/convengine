package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelSaveResponse {
    private boolean success;
    private String name;
    private Integer version;
    private String savedTo;
    private String note;
}

