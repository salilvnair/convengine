package com.github.salilvnair.convengine.engine.mcp.query.semantic.summary;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSemanticResultSummarizerTest {

    private final DefaultSemanticResultSummarizer summarizer = new DefaultSemanticResultSummarizer();

    @Test
    void summarizeReturnsNoMatchWhenNoRows() {
        String out = summarizer.summarize(new SemanticExecutionResult(0, List.of()), null);
        assertTrue(out.contains("No matching records found."));
    }

    @Test
    void summarizeReturnsMarkdownTableWhenRowsPresent() {
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("requestId", "REQ-1");
        r1.put("status", "FAILED");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("requestId", "REQ-2");
        r2.put("status", "PENDING");

        String out = summarizer.summarize(new SemanticExecutionResult(2, List.of(r1, r2)), null);

        assertTrue(out.contains("Found 2 matching record(s)."));
        assertTrue(out.contains("| requestId | status |"));
        assertTrue(out.contains("| --- | --- |"));
        assertTrue(out.contains("| REQ-1 | FAILED |"));
        assertTrue(out.contains("| REQ-2 | PENDING |"));
    }
}
