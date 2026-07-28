package com.github.salilvnair.convengine.engine.mcp;

/**
 * A tool discovered at runtime from a connected external MCP server.
 * Synthesized tool_code is "mcp.{serverId}.{toolName}".
 */
public record McpDiscoveredTool(
        String toolCode,
        String serverId,
        String toolName,
        String description
) {
    public static String buildToolCode(String serverId, String toolName) {
        return "mcp." + serverId + "." + toolName;
    }

    public static String extractServerId(String toolCode) {
        // toolCode format: mcp.<serverId>.<toolName>
        if (toolCode == null || !toolCode.startsWith("mcp.")) return null;
        String rest = toolCode.substring(4);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    public static String extractToolName(String toolCode) {
        if (toolCode == null || !toolCode.startsWith("mcp.")) return null;
        String rest = toolCode.substring(4);
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(dot + 1);
    }
}
