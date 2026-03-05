package com.github.salilvnair.convengine.engine.mcp.query.semantic.summary;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultSemanticResultSummarizer implements SemanticResultSummarizer {

    @Override
    public String summarize(SemanticExecutionResult result, SemanticQueryContext context) {
        if (result == null) {
            return "No result generated.";
        }
        if (result.rowCount() == 0) {
            return "No matching records found.";
        }
        return "Found " + result.rowCount() + " matching record(s).";
    }
}
