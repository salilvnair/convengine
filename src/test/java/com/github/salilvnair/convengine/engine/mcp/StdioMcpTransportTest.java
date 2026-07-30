package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No live MCP subprocess is spun up here — only what's verifiable without one:
 * spawn failure, isAlive() before/after close(), and close() idempotency.
 */
class StdioMcpTransportTest {

    @Test
    void constructorThrowsMcpExceptionWhenCommandCannotBeSpawned() {
        assertThrows(McpException.class, () ->
                new StdioMcpTransport("definitely-not-a-real-executable-xyz", List.of(), Map.of(), new ObjectMapper()));
    }

    @Test
    void isAliveTrueAfterSpawnThenFalseAfterClose() {
        // "cat" is a real, harmless subprocess that just echoes stdin; it is not
        // an MCP server, so we never call request()/notify() against it — only
        // process lifecycle is being verified here.
        StdioMcpTransport transport = new StdioMcpTransport("cat", List.of(), Map.of(), new ObjectMapper());
        try {
            assertTrue(transport.isAlive());
        } finally {
            transport.close();
        }
        assertFalse(transport.isAlive());
    }

    @Test
    void closeIsIdempotent() {
        StdioMcpTransport transport = new StdioMcpTransport("cat", List.of(), Map.of(), new ObjectMapper());
        transport.close();
        transport.close(); // should not throw
        assertFalse(transport.isAlive());
    }

    @Test
    void requestAfterCloseThrowsTransportClosed() {
        StdioMcpTransport transport = new StdioMcpTransport("cat", List.of(), Map.of(), new ObjectMapper());
        transport.close();
        McpException ex = assertThrows(McpException.class, () -> transport.request("tools/list", null));
        assertTrue(ex.getMessage().contains("closed"));
    }
}
