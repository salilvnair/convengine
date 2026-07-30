package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No live MCP SSE server is stood up here. The reader thread is started by the
 * constructor and will fail in the background against an unreachable host —
 * we only assert on things observable without a real counterpart: URL
 * normalization and close() being safe to call.
 */
class SseMcpTransportTest {

    @Test
    void appendsSseSuffixWhenUrlDoesNotAlreadyEndWithIt() throws Exception {
        SseMcpTransport transport = new SseMcpTransport("http://127.0.0.1:1/mcp", Map.of(), new ObjectMapper());
        try {
            assertEquals(URI.create("http://127.0.0.1:1/mcp/sse"), sseEndpointOf(transport));
        } finally {
            transport.close();
        }
    }

    @Test
    void doesNotDuplicateSseSuffixWhenUrlAlreadyEndsWithIt() throws Exception {
        SseMcpTransport transport = new SseMcpTransport("http://127.0.0.1:1/sse", Map.of(), new ObjectMapper());
        try {
            assertEquals(URI.create("http://127.0.0.1:1/sse"), sseEndpointOf(transport));
        } finally {
            transport.close();
        }
    }

    @Test
    void closeIsIdempotentAndSafeWithoutAConnection() {
        SseMcpTransport transport = new SseMcpTransport("http://127.0.0.1:1/mcp", Map.of(), new ObjectMapper());
        transport.close();
        transport.close(); // should not throw
        assertTrue(true);
    }

    private URI sseEndpointOf(SseMcpTransport transport) throws Exception {
        Field field = SseMcpTransport.class.getDeclaredField("sseEndpoint");
        field.setAccessible(true);
        return (URI) field.get(transport);
    }
}
