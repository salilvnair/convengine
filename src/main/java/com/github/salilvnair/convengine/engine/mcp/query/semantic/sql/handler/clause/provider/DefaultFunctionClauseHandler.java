package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstFunctionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
public class DefaultFunctionClauseHandler implements AstFunctionHandler {
    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        // Reserved for scalar-expression handlers (DATE_TRUNC, LOWER, COALESCE, etc.).
        // Current v1 runtime does not yet carry expression nodes for these functions.
    }
}

