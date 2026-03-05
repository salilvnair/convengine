package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;

public interface AstFunctionHandler {
    boolean supports(CompileWorkPlan plan);

    void apply(CompileWorkPlan plan);
}
