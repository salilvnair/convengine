package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class SemanticEmbeddingRebuildRequest {
    private String namespace;
    private Boolean clearNamespace = Boolean.TRUE;
    private Boolean includeEntities = Boolean.TRUE;
    private Boolean includeTables = Boolean.TRUE;
}

