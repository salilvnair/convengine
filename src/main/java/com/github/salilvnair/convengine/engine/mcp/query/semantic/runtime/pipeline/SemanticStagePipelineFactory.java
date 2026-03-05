package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline;

import com.github.salilvnair.convengine.engine.core.step.CoreStepDagOrderer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SemanticStagePipelineFactory {

    private static final Logger log = LoggerFactory.getLogger(SemanticStagePipelineFactory.class);

    private final List<SemanticQueryStage> discoveredStages;
    private final CoreStepDagOrderer dagOrderer;

    private SemanticStagePipeline pipeline;

    @PostConstruct
    public void init() {
        List<SemanticQueryStage> ordered = dagOrderer.order(
                discoveredStages,
                SemanticQueryStage.class,
                "Semantic query stage pipeline",
                c -> false
        );
        log.info("Semantic query stage order: {}",
                ordered.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.joining(" -> ")));
        this.pipeline = new SemanticStagePipeline(ordered);
    }

    public SemanticStagePipeline create() {
        return pipeline;
    }
}
