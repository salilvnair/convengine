package com.github.salilvnair.convengine.engine.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.agent.AgentConstants;
import com.github.salilvnair.convengine.engine.mcp.McpDiscoveredTool;
import com.github.salilvnair.convengine.engine.mcp.McpRegistry;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executor for tools discovered from external MCP servers at runtime.
 * Handles tool group MCP_SERVER — routes CALL_TOOL to the correct server
 * via McpRegistry using the synthesized tool_code format:
 *   mcp.{serverId}.{toolName}
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class McpServerToolExecutor implements AgentToolExecutor {

    private final McpRegistry mcpRegistry;
    private final ObjectMapper mapper;

    @Override
    public String toolGroup() {
        return AgentConstants.TOOL_GROUP_MCP_SERVER;
    }

    @Override
    public String execute(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        String toolCode = tool != null ? tool.getToolCode() : null;

        // tool may be null for runtime-discovered tools (not in ce_agent_tool)
        // fall back to the tool_code from the session's last plan
        if (toolCode == null) {
            Object lastToolCode = session.getInputParams() != null
                    ? session.getInputParams().get("TOOL_CODE")
                    : null;
            toolCode = lastToolCode != null ? lastToolCode.toString() : null;
        }

        String serverId = McpDiscoveredTool.extractServerId(toolCode);
        String toolName = McpDiscoveredTool.extractToolName(toolCode);

        if (serverId == null || toolName == null) {
            return JsonUtil.toJson(Map.of(
                    "status", "ERROR",
                    "error", "Cannot parse MCP server tool_code: " + toolCode
                            + ". Expected format: mcp.<serverId>.<toolName>"));
        }

        try {
            JsonNode argsNode = args == null || args.isEmpty()
                    ? mapper.nullNode()
                    : mapper.convertValue(args, JsonNode.class);

            log.debug("McpServerToolExecutor: calling server='{}' tool='{}'", serverId, toolName);
            JsonNode result = mcpRegistry.callTool(serverId, toolName, argsNode);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("McpServerToolExecutor: error calling server='{}' tool='{}'", serverId, toolName, e);
            return JsonUtil.toJson(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
