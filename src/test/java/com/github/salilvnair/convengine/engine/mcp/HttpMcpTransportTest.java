package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No live MCP HTTP server is stood up here — only what's verifiable without
 * one: constructor validation and failure-to-connect wrapping into McpException.
 */
class HttpMcpTransportTest {

    @Test
    void constructorRejectsMalformedUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new HttpMcpTransport("not a url", Map.of(), new ObjectMapper()));
    }

    @Test
    void requestWrapsConnectionFailureInMcpException() {
        // Port 1 is a reserved/unassigned TCP port — connection is refused
        // immediately rather than hanging until the connect timeout.
        HttpMcpTransport transport = new HttpMcpTransport("http://127.0.0.1:1/rpc", Map.of(), new ObjectMapper());
        McpException ex = assertThrows(McpException.class, () -> transport.request("tools/list", null));
        assertTrue(ex.getMessage().contains("MCP HTTP call failed"));
    }

    @Test
    void isAliveDefaultsToTrueAndCloseIsIdempotent() {
        HttpMcpTransport transport = new HttpMcpTransport("http://127.0.0.1:1/rpc", Map.of(), new ObjectMapper());
        assertTrue(transport.isAlive());
        transport.close();
        transport.close(); // should not throw
    }
}
