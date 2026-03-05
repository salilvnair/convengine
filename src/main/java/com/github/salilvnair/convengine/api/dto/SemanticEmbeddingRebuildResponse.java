package com.github.salilvnair.convengine.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SemanticEmbeddingRebuildResponse {
    private boolean success;
    private boolean embeddingEnabled;
    private String namespace;
    private int candidateCount;
    private int indexedCount;
    private int failedCount;
    private String message;
}

