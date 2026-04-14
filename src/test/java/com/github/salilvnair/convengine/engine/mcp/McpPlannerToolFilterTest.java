package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.entity.CeMcpTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPlannerToolFilterTest {

    @Test
    void extractsToolCodesFromBacktickedPromptSegments() {
        Set<String> codes = McpPlanner.extractMentionedToolCodes("""
                Use only:
                `db.semantic.query`
                `postgres.query`
                """, "Other token: `not_a_tool`");

        assertTrue(codes.contains("db.semantic.query"));
        assertTrue(codes.contains("postgres.query"));
        assertFalse(codes.contains("not_a_tool"));
    }

    @Test
    void filtersAvailableToolsToMentionedCodes() {
        List<CeMcpTool> tools = List.of(
                tool("db.semantic.query"),
                tool("postgres.query"),
                tool("order.status.api"));

        List<CeMcpTool> filtered = McpPlanner.filterToolsByMentionedCodes(
                tools, Set.of("db.semantic.query", "postgres.query"));

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(t -> "db.semantic.query".equalsIgnoreCase(t.getToolCode())));
        assertTrue(filtered.stream().anyMatch(t -> "postgres.query".equalsIgnoreCase(t.getToolCode())));
        assertFalse(filtered.stream().anyMatch(t -> "order.status.api".equalsIgnoreCase(t.getToolCode())));
    }

    private CeMcpTool tool(String code) {
        CeMcpTool tool = new CeMcpTool();
        tool.setToolCode(code);
        tool.setToolGroup("DB");
        tool.setIntentCode("ANY");
        tool.setStateCode("ANY");
        tool.setEnabled(true);
        return tool;
    }
}
