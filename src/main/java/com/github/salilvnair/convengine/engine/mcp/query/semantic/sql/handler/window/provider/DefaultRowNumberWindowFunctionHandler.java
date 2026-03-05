package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core.AstWindowFunctionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
public class DefaultRowNumberWindowFunctionHandler implements AstWindowFunctionHandler {
    @Override
    public boolean supports(String functionName) {
        return functionName != null && SemanticSqlConstants.WINDOW_FN_ROW_NUMBER.equalsIgnoreCase(functionName);
    }

    @Override
    public String renderExpression(String overClause, CanonicalWindowSpec windowSpec) {
        if (overClause == null || overClause.isBlank()) {
            return "ROW_NUMBER() OVER ()";
        }
        return "ROW_NUMBER() OVER (" + overClause + ")";
    }
}
