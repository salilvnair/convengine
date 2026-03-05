package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AstWindowFunctionHandlerRegistry {
    private final List<AstWindowFunctionHandler> handlers;

    public AstWindowFunctionHandlerRegistry(List<AstWindowFunctionHandler> handlers) {
        List<AstWindowFunctionHandler> sorted = new ArrayList<>(handlers == null ? List.of() : handlers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.handlers = List.copyOf(sorted);
    }

    public AstWindowFunctionHandler resolve(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return null;
        }
        for (AstWindowFunctionHandler handler : handlers) {
            if (handler.supports(functionName)) {
                return handler;
            }
        }
        return null;
    }
}

