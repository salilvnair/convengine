package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
@Component
public class McpToolRegistry {

    private final StaticConfigurationCacheService staticCacheService;

    public List<CeMcpTool> listEnabledTools(String intentCode, String stateCode) {
        return staticCacheService.findEnabledMcpTools(intentCode, stateCode);
    }

    public CeMcpTool requireTool(String toolCode, String intentCode, String stateCode) {
        return staticCacheService.findMcpTool(toolCode, intentCode, stateCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled MCP tool for current intent/state: " + toolCode));
    }

    public CeMcpDbTool requireDbTool(String toolCode) {
        return staticCacheService.findMcpDbTool(toolCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled DB tool in ce_mcp_db_tool for toolCode=" + toolCode
                                + ". This row is required only when no matching DbToolHandler is registered."));
    }

    public String normalizeToolGroup(String toolGroup) {
        if (toolGroup == null || toolGroup.isBlank()) {
            return McpConstants.TOOL_GROUP_DB;
        }
        String normalized = toolGroup.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DB", "MCP_DB", "DATABASE", "SQL" -> McpConstants.TOOL_GROUP_DB;
            case "HTTP", "HTTP_API", "API" -> McpConstants.TOOL_GROUP_HTTP_API;
            case "ACTION", "WORKFLOW_ACTION", "WORKFLOW" -> McpConstants.TOOL_GROUP_WORKFLOW_ACTION;
            case "DOC", "DOCUMENT", "DOCUMENT_RETRIEVAL", "RAG" -> McpConstants.TOOL_GROUP_DOCUMENT_RETRIEVAL;
            case "CALC", "CALCULATOR", "TRANSFORM", "CALCULATOR_TRANSFORM" -> McpConstants.TOOL_GROUP_CALCULATOR_TRANSFORM;
            case "NOTIFY", "NOTIFICATION" -> McpConstants.TOOL_GROUP_NOTIFICATION;
            case "FILE", "FILES" -> McpConstants.TOOL_GROUP_FILES;
            default -> normalized;
        };
    }
}
