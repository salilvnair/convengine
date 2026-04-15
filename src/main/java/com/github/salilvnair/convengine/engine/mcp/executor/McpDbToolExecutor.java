package com.github.salilvnair.convengine.engine.mcp.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.McpDbExecutor;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
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
    private final ConvEngineMcpConfig mcpConfig;
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
        if (isSemanticQueryToolCode(toolCode)) {
            throw new IllegalStateException(buildSemanticQueryHandlerMissingMessage(toolCode));
        }
        CeMcpDbTool dbTool = registry.requireDbTool(toolCode);
        return dbExecutor.execute(dbTool, args, session);
    }

    private boolean isSemanticQueryToolCode(String toolCode) {
        String configured = mcpConfig != null
                && mcpConfig.getDb() != null
                && mcpConfig.getDb().getSemantic() != null
                ? mcpConfig.getDb().getSemantic().getToolCode()
                : "db.semantic.query";
        String expected = (configured == null || configured.isBlank()) ? "db.semantic.query" : configured.trim();
        return toolCode != null && expected.equalsIgnoreCase(toolCode.trim());
    }

    private String buildSemanticQueryHandlerMissingMessage(String toolCode) {
        boolean enabled = mcpConfig != null
                && mcpConfig.getDb() != null
                && mcpConfig.getDb().getSemantic() != null
                && mcpConfig.getDb().getSemantic().isEnabled();
        if (!enabled) {
            return "Semantic query tool handler is not registered for toolCode=" + toolCode
                    + ". Enable convengine.mcp.db.semantic.enabled=true.";
        }
        return "Semantic query tool handler is not registered for toolCode=" + toolCode
                + " even though convengine.mcp.db.semantic.enabled=true. "
                + "Check bean registration for DbSemanticQueryToolHandler.";
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
            String summary = describeValue(value);
            String cause = e.getClass().getName() + ": " + e.getMessage();
            throw new IllegalStateException("Failed to serialize DB tool response. value=" + summary + ", cause=" + cause, e);
        }
    }

    private String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return "Map(size=" + map.size() + ", keys=" + summarizeKeys(map) + ")";
        }
        if (value instanceof List<?> list) {
            return "List(size=" + list.size() + ", elementTypes=" + summarizeElementTypes(list) + ")";
        }
        return value.getClass().getName();
    }

    private String summarizeKeys(Map<?, ?> map) {
        int limit = 10;
        StringBuilder out = new StringBuilder("[");
        int count = 0;
        for (Object key : map.keySet()) {
            if (count > 0) {
                out.append(", ");
            }
            out.append(String.valueOf(key));
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (map.size() > limit) {
            out.append(", …");
        }
        out.append("]");
        return out.toString();
    }

    private String summarizeElementTypes(List<?> list) {
        int limit = 10;
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            if (item == null) {
                types.add("null");
            } else {
                types.add(item.getClass().getName());
            }
            if (types.size() >= limit) {
                break;
            }
        }
        return types.toString();
    }
}
