package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstFunctionHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstFunctionHandlerRegistry {
    private final List<AstFunctionHandler> handlers;

    public AstFunctionHandlerRegistry(List<AstFunctionHandler> handlers) {
        List<AstFunctionHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public List<AstFunctionHandler> handlers() {
        return handlers;
    }
}
