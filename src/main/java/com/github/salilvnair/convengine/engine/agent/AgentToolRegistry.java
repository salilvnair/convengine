package com.github.salilvnair.convengine.engine.agent;

import com.github.salilvnair.convengine.entity.CeAgentDbTool;
import com.github.salilvnair.convengine.entity.CeAgentTool;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
@Component
public class AgentToolRegistry {

    private final StaticConfigurationCacheService staticCacheService;

    public List<CeAgentTool> listEnabledTools(String intentCode, String stateCode) {
        return staticCacheService.findEnabledMcpTools(intentCode, stateCode);
    }

    public CeAgentTool requireTool(String toolCode, String intentCode, String stateCode) {
        return staticCacheService.findMcpTool(toolCode, intentCode, stateCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled MCP tool for current intent/state: " + toolCode));
    }

    public CeAgentDbTool requireDbTool(String toolCode) {
        return staticCacheService.findMcpDbTool(toolCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled DB tool in ce_mcp_db_tool for toolCode=" + toolCode
                                + ". This row is required only when no matching DbToolHandler is registered."));
    }

    public String normalizeToolGroup(String toolGroup) {
        if (toolGroup == null || toolGroup.isBlank()) {
            return AgentConstants.TOOL_GROUP_DB;
        }
        String normalized = toolGroup.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DB", "MCP_DB", "DATABASE", "SQL" -> AgentConstants.TOOL_GROUP_DB;
            case "HTTP", "HTTP_API", "API" -> AgentConstants.TOOL_GROUP_HTTP_API;
            case "ACTION", "WORKFLOW_ACTION", "WORKFLOW" -> AgentConstants.TOOL_GROUP_WORKFLOW_ACTION;
            case "DOC", "DOCUMENT", "DOCUMENT_RETRIEVAL", "RAG" -> AgentConstants.TOOL_GROUP_DOCUMENT_RETRIEVAL;
            case "CALC", "CALCULATOR", "TRANSFORM", "CALCULATOR_TRANSFORM" -> AgentConstants.TOOL_GROUP_CALCULATOR_TRANSFORM;
            case "NOTIFY", "NOTIFICATION" -> AgentConstants.TOOL_GROUP_NOTIFICATION;
            case "FILE", "FILES" -> AgentConstants.TOOL_GROUP_FILES;
            case "MCP", "MCP_SERVER", "EXTERNAL_MCP" -> AgentConstants.TOOL_GROUP_MCP_SERVER;
            default -> normalized;
        };
    }
}
