package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class SemanticModelReloadRequest {
    private String yaml;
    private Boolean useSavedModel = false;
    private String name = "default";
}

