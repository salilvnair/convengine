package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstClauseHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstClauseHandlerRegistry {
    private final List<AstClauseHandler> handlers;

    public AstClauseHandlerRegistry(List<AstClauseHandler> handlers) {
        List<AstClauseHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public void applyAll(CompileWorkPlan plan) {
        for (AstClauseHandler handler : handlers) {
            if (handler.supports(plan)) {
                handler.apply(plan);
            }
        }
    }
}
