package com.github.salilvnair.convengine.engine.mcp.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.McpDbExecutor;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpDbToolExecutor implements McpToolExecutor {

    private final McpDbExecutor dbExecutor;
    private final McpToolRegistry registry;
    private final List<DbToolHandler> toolHandlers;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toolGroup() {
        return McpConstants.TOOL_GROUP_DB;
    }

    @Override
    public String execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        String toolCode = tool == null ? null : tool.getToolCode();
        String toolGroup = tool == null ? null : tool.getToolGroup();
        if (toolCode != null && !toolCode.isBlank()) {
            for (DbToolHandler handler : toolHandlers) {
                String candidate = handler.toolCode();
                if (candidate != null && candidate.trim().equalsIgnoreCase(toolCode.trim())) {
                    return normalizeResult(handler.execute(tool, args == null ? Map.of() : args, session));
                }
            }
        }
        String normalizedGroup = registry.normalizeToolGroup(toolGroup);
        if (!McpConstants.TOOL_GROUP_DB.equalsIgnoreCase(normalizedGroup)) {
            throw new IllegalStateException(
                    "McpDbToolExecutor can execute only DB tools. toolCode=" + toolCode + ", toolGroup=" + toolGroup);
        }
        CeMcpDbTool dbTool = registry.requireDbTool(toolCode);
        return dbExecutor.execute(dbTool, args);
    }

    private String normalizeResult(Object value) {
        if (value == null) {
            return McpConstants.EMPTY_JSON_OBJECT;
        }
        try {
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) {
                    return McpConstants.EMPTY_JSON_OBJECT;
                }
                try {
                    mapper.readTree(trimmed);
                    return trimmed;
                } catch (Exception ignored) {
                    return mapper.writeValueAsString(Map.of(ConvEnginePayloadKey.TEXT, trimmed));
                }
            }
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize DB tool response", e);
        }
    }
}
