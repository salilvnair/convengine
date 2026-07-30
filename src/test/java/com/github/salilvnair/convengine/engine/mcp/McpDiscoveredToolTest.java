package com.github.salilvnair.convengine.engine.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpDiscoveredToolTest {

    @Test
    void buildToolCodeFormatsAsMcpServerIdToolName() {
        String toolCode = McpDiscoveredTool.buildToolCode("srv1", "search_web");
        assertEquals("mcp.srv1.search_web", toolCode);
    }

    @Test
    void extractServerIdAndToolNameRoundTripBuiltCode() {
        String toolCode = McpDiscoveredTool.buildToolCode("weather-srv", "get_forecast");
        assertEquals("weather-srv", McpDiscoveredTool.extractServerId(toolCode));
        assertEquals("get_forecast", McpDiscoveredTool.extractToolName(toolCode));
    }

    @Test
    void extractServerIdAndToolNameHandleToolNameWithDots() {
        // toolName itself may contain dots — only the first dot after "mcp." separates serverId.
        String toolCode = "mcp.srv1.namespace.tool";
        assertEquals("srv1", McpDiscoveredTool.extractServerId(toolCode));
        assertEquals("namespace.tool", McpDiscoveredTool.extractToolName(toolCode));
    }

    @Test
    void extractServerIdReturnsNullForNullInput() {
        assertNull(McpDiscoveredTool.extractServerId(null));
        assertNull(McpDiscoveredTool.extractToolName(null));
    }

    @Test
    void extractServerIdReturnsNullForNonMcpPrefix() {
        assertNull(McpDiscoveredTool.extractServerId("http.srv1.tool"));
        assertNull(McpDiscoveredTool.extractToolName("http.srv1.tool"));
    }

    @Test
    void extractServerIdReturnsWholeRestWhenNoSecondDot() {
        // "mcp.srv1" — no dot after the serverId, so toolName can't be split out.
        assertEquals("srv1", McpDiscoveredTool.extractServerId("mcp.srv1"));
        assertNull(McpDiscoveredTool.extractToolName("mcp.srv1"));
    }

    @Test
    void extractServerIdReturnsEmptyForBareMcpPrefix() {
        assertEquals("", McpDiscoveredTool.extractServerId("mcp."));
        assertNull(McpDiscoveredTool.extractToolName("mcp."));
    }
}
