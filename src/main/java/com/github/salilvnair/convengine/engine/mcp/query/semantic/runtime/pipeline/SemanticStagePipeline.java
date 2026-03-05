package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;

import java.util.List;

public class SemanticStagePipeline {

    private final List<SemanticQueryStage> orderedStages;

    public SemanticStagePipeline(List<SemanticQueryStage> orderedStages) {
        this.orderedStages = orderedStages == null ? List.of() : List.copyOf(orderedStages);
    }

    public List<SemanticQueryStage> stages() {
        return orderedStages;
    }
}
