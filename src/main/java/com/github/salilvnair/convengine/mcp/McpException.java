package com.github.salilvnair.convengine.mcp;

/**
 * Unified exception for MCP (Model Context Protocol) failures — transport
 * errors, JSON-RPC error responses, protocol-level violations.
 */
public class McpException extends RuntimeException {
    private final int code;

    public McpException(String message) { super(message); this.code = 0; }
    public McpException(String message, Throwable cause) { super(message, cause); this.code = 0; }
    public McpException(int code, String message) { super(message); this.code = code; }

    /** JSON-RPC error code from the server, or {@code 0} if the failure was local. */
    public int getCode() { return code; }
}
