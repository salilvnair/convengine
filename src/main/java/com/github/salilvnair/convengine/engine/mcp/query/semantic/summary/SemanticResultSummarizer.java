package com.github.salilvnair.convengine.engine.mcp.query.semantic.summary;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;

public interface SemanticResultSummarizer {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    String summarize(SemanticExecutionResult result, SemanticQueryContext context);
}
