package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Low-level JSON-RPC 2.0 transport for talking to an MCP server. Implementations
 * are responsible for framing (line-delimited JSON for stdio, request/response
 * for HTTP) and for preserving request↔response correlation by {@code id}.
 *
 * All methods may be called from any thread; implementations must synchronize
 * internally. {@link #close()} is idempotent.
 */
public interface McpTransport extends AutoCloseable {

    /** Send a JSON-RPC request and block until the matching response returns.
     *
     *  @param method  e.g. {@code "tools/list"}, {@code "tools/call"}
     *  @param params  serialized params object (may be {@code null})
     *  @return        the {@code result} node from the JSON-RPC response
     *  @throws McpException if the server returned an error object, or on I/O /
     *                       timeout / malformed-message failures.
     */
    JsonNode request(String method, JsonNode params) throws McpException;

    /** Send a fire-and-forget JSON-RPC notification (no id, no response). */
    void notify(String method, JsonNode params) throws McpException;

    @Override
    void close();

    /** Whether the underlying connection/process is still usable. */
    default boolean isAlive() { return true; }
}
