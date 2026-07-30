package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No MockMvc/web layer convention exists elsewhere in this codebase for
 * controller tests, so these invoke McpController's handler methods directly
 * against a mocked McpRegistry — matching the direct-instantiation style used
 * by PostgresQueryToolHandlerTest.
 */
class McpControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listServersDelegatesToRegistry() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerConfig cfg = McpServerConfig.builder().id("s1").name("srv one").build();
        when(registry.list()).thenReturn(List.of(cfg));

        McpController controller = new McpController(registry, mapper);
        List<McpServerConfig> result = controller.listServers();

        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).getId());
    }

    @Test
    void upsertServerReturnsOkOnSuccess() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerConfig cfg = McpServerConfig.builder().id("s1").name("srv one").build();
        when(registry.upsert(any())).thenReturn(cfg);

        McpController controller = new McpController(registry, mapper);
        ResponseEntity<?> resp = controller.upsertServer(cfg);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(cfg, resp.getBody());
    }

    @Test
    void upsertServerReturnsBadRequestOnFailure() {
        McpRegistry registry = mock(McpRegistry.class);
        McpServerConfig cfg = McpServerConfig.builder().build();
        when(registry.upsert(any())).thenThrow(new McpException("bad config"));

        McpController controller = new McpController(registry, mapper);
        ResponseEntity<?> resp = controller.upsertServer(cfg);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(Map.of("error", "bad config"), resp.getBody());
    }

    @Test
    void deleteServerAlwaysReturnsOk() {
        McpRegistry registry = mock(McpRegistry.class);
        McpController controller = new McpController(registry, mapper);

        ResponseEntity<Map<String, Object>> resp = controller.deleteServer("s1");

        verify(registry).remove("s1");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Map.of("ok", true), resp.getBody());
    }

    @Test
    void listToolsUsesCacheByDefaultAndRefreshFlagForcesRefresh() {
        McpRegistry registry = mock(McpRegistry.class);
        JsonNode tool = mapper.createObjectNode().put("name", "search");
        when(registry.listTools("s1")).thenReturn(List.of(tool));
        when(registry.refresh("s1")).thenReturn(List.of(tool));

        McpController controller = new McpController(registry, mapper);

        ResponseEntity<?> cachedResp = controller.listTools("s1", false);
        verify(registry).listTools("s1");
        assertEquals(200, cachedResp.getStatusCode().value());

        ResponseEntity<?> refreshedResp = controller.listTools("s1", true);
        verify(registry).refresh("s1");
        assertEquals(200, refreshedResp.getStatusCode().value());
    }

    @Test
    void listToolsReturnsBadRequestOnFailure() {
        McpRegistry registry = mock(McpRegistry.class);
        when(registry.listTools("bad")).thenThrow(new McpException("unknown MCP server: bad"));

        McpController controller = new McpController(registry, mapper);
        ResponseEntity<?> resp = controller.listTools("bad", false);

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("unknown MCP server"));
    }

    @Test
    void callToolConvertsArgumentsAndReturnsResult() {
        McpRegistry registry = mock(McpRegistry.class);
        JsonNode result = mapper.createObjectNode().put("ok", true);
        when(registry.callTool(eq("s1"), eq("search"), any())).thenReturn(result);

        McpController controller = new McpController(registry, mapper);
        McpController.CallBody body = new McpController.CallBody();
        body.setArguments(Map.of("q", "hello"));

        ResponseEntity<?> resp = controller.callTool("s1", "search", body);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> respBody = (Map<?, ?>) resp.getBody();
        assertEquals("s1", respBody.get("serverId"));
        assertEquals("search", respBody.get("tool"));
        verify(registry).callTool(eq("s1"), eq("search"), any());
    }

    @Test
    void callToolHandlesNullBody() {
        McpRegistry registry = mock(McpRegistry.class);
        JsonNode result = mapper.createObjectNode().put("ok", true);
        when(registry.callTool(eq("s1"), eq("ping"), isNull())).thenReturn(result);

        McpController controller = new McpController(registry, mapper);
        ResponseEntity<?> resp = controller.callTool("s1", "ping", null);

        assertEquals(200, resp.getStatusCode().value());
        verify(registry).callTool("s1", "ping", null);
    }

    @Test
    void callToolReturnsBadRequestOnFailure() {
        McpRegistry registry = mock(McpRegistry.class);
        when(registry.callTool(eq("s1"), eq("search"), any())).thenThrow(new McpException("boom"));

        McpController controller = new McpController(registry, mapper);
        McpController.CallBody body = new McpController.CallBody();
        body.setArguments(Map.of("q", "hello"));

        ResponseEntity<?> resp = controller.callTool("s1", "search", body);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(Map.of("error", "boom"), resp.getBody());
    }
}
