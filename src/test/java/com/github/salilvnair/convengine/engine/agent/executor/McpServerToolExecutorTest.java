package com.github.salilvnair.convengine.engine.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.agent.AgentConstants;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.McpException;
import com.github.salilvnair.convengine.engine.mcp.McpRegistry;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerToolExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolGroupIsMcpServer() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);
        assertEquals(AgentConstants.TOOL_GROUP_MCP_SERVER, executor.toolGroup());
    }

    @Test
    void executeParsesToolCodeAndDelegatesToRegistry() {
        McpRegistry registry = mock(McpRegistry.class);
        JsonNode result = mapper.createObjectNode().put("ok", true);
        when(registry.callTool(eq("weather-srv"), eq("get_forecast"), any())).thenReturn(result);

        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);

        CeAgentTool tool = new CeAgentTool();
        tool.setToolCode("mcp.weather-srv.get_forecast");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "Seattle");

        String out = executor.execute(tool, args, session("what's the weather"));

        verify(registry).callTool(eq("weather-srv"), eq("get_forecast"), any());
        assertTrue(out.contains("\"ok\":true"));
    }

    @Test
    void executeReturnsJsonErrorPayloadWhenToolCodeIsUnparseable() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);

        CeAgentTool tool = new CeAgentTool();
        tool.setToolCode("not-an-mcp-tool-code");

        String out = executor.execute(tool, Map.of(), session("do something"));

        assertTrue(out.contains("\"status\":\"ERROR\""));
        assertTrue(out.contains("Cannot parse MCP server tool_code"));
    }

    @Test
    void executeReturnsJsonErrorPayloadWhenToolIsNullAndSessionHasNoToolCode() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);

        String out = executor.execute(null, Map.of(), session("do something"));

        assertTrue(out.contains("\"status\":\"ERROR\""));
    }

    @Test
    void executeFallsBackToSessionInputParamsWhenCeAgentToolArgIsNull() {
        McpRegistry registry = mock(McpRegistry.class);
        JsonNode result = mapper.createObjectNode().put("ok", true);
        when(registry.callTool(eq("srv1"), eq("search"), any())).thenReturn(result);

        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);

        EngineSession session = session("search something");
        session.getInputParams().put("TOOL_CODE", "mcp.srv1.search");

        String out = executor.execute(null, Map.of("q", "hi"), session);

        verify(registry).callTool(eq("srv1"), eq("search"), any());
        assertTrue(out.contains("\"ok\":true"));
    }

    @Test
    void executeReturnsJsonErrorPayloadWhenRegistryThrows() {
        McpRegistry registry = mock(McpRegistry.class);
        when(registry.callTool(eq("srv1"), eq("search"), any())).thenThrow(new McpException("server unreachable"));

        McpServerToolExecutor executor = new McpServerToolExecutor(registry, mapper);

        CeAgentTool tool = new CeAgentTool();
        tool.setToolCode("mcp.srv1.search");

        String out = executor.execute(tool, Map.of("q", "hi"), session("search"));

        assertTrue(out.contains("\"status\":\"ERROR\""));
        assertTrue(out.contains("server unreachable"));
    }

    private EngineSession session(String userText) {
        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText)
                .inputParams(new LinkedHashMap<>())
                .build();
        return new EngineSession(context, new ObjectMapper());
    }
}
