package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstSubqueryHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstSubqueryHandlerRegistry {
    private final List<AstSubqueryHandler> handlers;

    public AstSubqueryHandlerRegistry(List<AstSubqueryHandler> handlers) {
        List<AstSubqueryHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public List<AstSubqueryHandler> handlers() {
        return handlers;
    }
}
