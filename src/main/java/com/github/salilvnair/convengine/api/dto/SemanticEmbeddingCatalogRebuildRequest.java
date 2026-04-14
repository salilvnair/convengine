package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class SemanticEmbeddingCatalogRebuildRequest {
    private String queryClassKey;
    private String entityKey;
    private Boolean onlyMissing = Boolean.TRUE;
    private Integer limit = 200;
    private String embeddingModel;
    private String embeddingVersion;
}

