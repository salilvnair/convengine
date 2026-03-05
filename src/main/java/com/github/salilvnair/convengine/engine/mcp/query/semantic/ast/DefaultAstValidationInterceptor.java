package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultAstValidationInterceptor implements AstValidationInterceptor {
}
