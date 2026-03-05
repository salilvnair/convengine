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

    private final SemanticModelLoader loader;
    private final SemanticModelValidator validator;

    @Getter
    private volatile SemanticModel model = new SemanticModel(1, "", "", null, null, null, null);

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        SemanticModel loaded = loader.loadOrEmpty();
        List<String> errors = validator.validate(loaded);
        if (!errors.isEmpty()) {
            log.warn("Semantic model validation errors: {}", errors);
        }
        this.model = loaded;
    }
}
