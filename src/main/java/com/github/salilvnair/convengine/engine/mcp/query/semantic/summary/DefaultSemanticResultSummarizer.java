package com.github.salilvnair.convengine.engine.mcp.query.semantic.summary;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Order()
public class DefaultSemanticResultSummarizer implements SemanticResultSummarizer {

    @Override
    public String summarize(SemanticExecutionResult result, SemanticQueryContext context) {
        if (result == null) {
            return "No result generated.";
        }
        if (result.rowCount() == 0) {
            return "No matching records found.";
        }
        return buildMarkdownTable(result);
    }

    private String buildMarkdownTable(SemanticExecutionResult result) {
        List<Map<String, Object>> rows = result.rows() == null ? List.of() : result.rows();
        if (rows.isEmpty()) {
            return "Found " + result.rowCount() + " matching record(s).";
        }

        List<String> headers = new ArrayList<>();
        Map<String, Object> first = rows.getFirst();
        if (first != null) {
            headers.addAll(first.keySet().stream().filter(Objects::nonNull).map(String::valueOf).toList());
        }
        if (headers.isEmpty()) {
            return "Found " + result.rowCount() + " matching record(s).";
        }

        StringBuilder out = new StringBuilder();
        out.append("Found ").append(result.rowCount()).append(" matching record(s).").append("\n\n");
        out.append("| ").append(String.join(" | ", headers)).append(" |").append("\n");
        out.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            out.append("---");
            if (i < headers.size() - 1) {
                out.append(" | ");
            }
        }
        out.append(" |").append("\n");

        for (Map<String, Object> row : rows) {
            out.append("| ");
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                Object value = row == null ? null : row.get(header);
                out.append(escapeCell(stringify(value)));
                if (i < headers.size() - 1) {
                    out.append(" | ");
                }
            }
            out.append(" |").append("\n");
        }
        return out.toString().trim();
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private String escapeCell(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replace("|", "\\|")
                .replace("\n", "<br/>")
                .replace("\r", "");
    }
}
