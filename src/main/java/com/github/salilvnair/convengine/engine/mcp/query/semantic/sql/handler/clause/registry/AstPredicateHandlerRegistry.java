package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstPredicateHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstPredicateHandlerRegistry {
    private final List<AstPredicateHandler> handlers;

    public AstPredicateHandlerRegistry(List<AstPredicateHandler> handlers) {
        List<AstPredicateHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public List<AstPredicateHandler> handlers() {
        return handlers;
    }
}
