package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelRegistry {

    private final SemanticModelDynamicOverlayService dynamicOverlayService;
    private final SemanticModelValidator validator;

    @Getter
    private volatile SemanticModel model = new SemanticModel(1, "", "", null, null, null, null, null);

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        SemanticModel loaded = dynamicOverlayService.apply(new SemanticModel(1, "", "", null, null, null, null, null));
        List<String> errors = validator.validate(loaded);
        if (!errors.isEmpty()) {
            log.warn("Semantic model validation errors: {}", errors);
        }
        this.model = loaded;
    }

    public synchronized void setModel(SemanticModel newModel) {
        if (newModel == null) {
            return;
        }
        List<String> errors = validator.validate(newModel);
        if (!errors.isEmpty()) {
            log.warn("Semantic model validation errors: {}", errors);
        }
        this.model = newModel;
    }
}
