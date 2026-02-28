package com.github.salilvnair.convengine.engine.mcp.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiProcessorToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiExecutorAdapter;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiRequestingToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.http.HttpApiRequestSpec;
import com.github.salilvnair.convengine.engine.mcp.executor.http.HttpApiToolInvoker;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class McpHttpApiToolExecutor implements McpToolExecutor {

    private final List<HttpApiToolHandler> toolHandlers;
    private final Optional<HttpApiExecutorAdapter> adapter;
    private final HttpApiToolInvoker invoker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toolGroup() {
        return McpConstants.TOOL_GROUP_HTTP_API;
    }

    @Override
    public String execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        if (tool == null || tool.getToolCode() == null || tool.getToolCode().isBlank()) {
            throw new IllegalStateException("HTTP_API tool execution requires a resolvable tool_code");
        }

        String toolCode = tool.getToolCode().trim();
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        for (HttpApiToolHandler handler : toolHandlers) {
                String candidate = handler.toolCode();
                if (candidate != null && candidate.trim().equalsIgnoreCase(toolCode)) {
                    if (handler instanceof HttpApiProcessorToolHandler apiProcessorHandler) {
                        return normalizeResult(invoker.invokeUsingApiProcessor(toolCode, apiProcessorHandler, tool, safeArgs, session));
                    }
                    if (handler instanceof HttpApiRequestingToolHandler requestingHandler) {
                        HttpApiRequestSpec requestSpec = requestingHandler.requestSpec(tool, safeArgs, session);
                        return normalizeResult(invoker.invoke(toolCode, requestSpec, tool, safeArgs, session));
                    }
                    return normalizeResult(handler.execute(tool, safeArgs, session));
                }
            }

        return adapter
                .map(value -> normalizeResult(value.execute(tool, safeArgs, session)))
                .orElseThrow(() -> new IllegalStateException(
                        "No HttpApiToolHandler or HttpApiExecutorAdapter configured for tool_code="
                                + toolCode.toUpperCase(Locale.ROOT)));
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
            throw new IllegalStateException("Failed to serialize HTTP_API tool response", e);
        }
    }
}
