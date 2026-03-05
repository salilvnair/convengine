package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstOperatorHandlerRegistry {
    private final List<AstOperatorHandler> handlers;

    public AstOperatorHandlerRegistry(List<AstOperatorHandler> handlers) {
        List<AstOperatorHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public AstOperatorHandler resolve(AstOperator operator) {
        if (operator == null) {
            return null;
        }
        for (AstOperatorHandler handler : handlers) {
            if (handler.supports(operator)) {
                return handler;
            }
        }
        return null;
    }

    public String sqlToken(AstOperator operator) {
        AstOperatorHandler handler = resolve(operator);
        return handler == null ? null : handler.sqlToken(operator);
    }
}

