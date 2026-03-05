package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstExistsHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstExistsHandlerRegistry {
    private final List<AstExistsHandler> handlers;

    public AstExistsHandlerRegistry(List<AstExistsHandler> handlers) {
        List<AstExistsHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public List<AstExistsHandler> handlers() {
        return handlers;
    }
}
