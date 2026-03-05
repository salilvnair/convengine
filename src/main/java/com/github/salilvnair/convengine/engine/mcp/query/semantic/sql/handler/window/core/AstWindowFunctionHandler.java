package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;

public interface AstWindowFunctionHandler {
    boolean supports(String functionName);

    String renderExpression(String overClause, CanonicalWindowSpec windowSpec);
}

