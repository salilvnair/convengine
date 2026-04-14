package com.github.salilvnair.convengine.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SemanticEmbeddingCatalogRebuildResponse {
    private boolean success;
    private int candidateCount;
    private int indexedCount;
    private int failedCount;
    private int skippedCount;
    private String queryClassKey;
    private String entityKey;
    private String message;
}

