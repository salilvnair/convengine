package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstWindowHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstWindowHandlerRegistry {
    private final List<AstWindowHandler> handlers;

    public AstWindowHandlerRegistry(List<AstWindowHandler> handlers) {
        List<AstWindowHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public List<AstWindowHandler> handlers() {
        return handlers;
    }
}
