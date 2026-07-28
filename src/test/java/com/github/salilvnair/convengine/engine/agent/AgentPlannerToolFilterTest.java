package com.github.salilvnair.convengine.engine.agent;

import com.github.salilvnair.convengine.entity.CeAgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlannerToolFilterTest {

    @Test
    void extractsToolCodesFromBacktickedPromptSegments() {
        Set<String> codes = AgentPlanner.extractMentionedToolCodes("""
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
        List<CeAgentTool> tools = List.of(
                tool("db.semantic.query"),
                tool("postgres.query"),
                tool("order.status.api"));

        List<CeAgentTool> filtered = AgentPlanner.filterToolsByMentionedCodes(
                tools, Set.of("db.semantic.query", "postgres.query"));

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(t -> "db.semantic.query".equalsIgnoreCase(t.getToolCode())));
        assertTrue(filtered.stream().anyMatch(t -> "postgres.query".equalsIgnoreCase(t.getToolCode())));
        assertFalse(filtered.stream().anyMatch(t -> "order.status.api".equalsIgnoreCase(t.getToolCode())));
    }

    private CeAgentTool tool(String code) {
        CeAgentTool tool = new CeAgentTool();
        tool.setToolCode(code);
        tool.setToolGroup("DB");
        tool.setIntentCode("ANY");
        tool.setStateCode("ANY");
        tool.setEnabled(true);
        return tool;
    }
}
