package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class SemanticModelSaveRequest {
    private String yaml;
    private String name = "default";
    private Integer version = 1;
    private Boolean persistToDb = true;
}

