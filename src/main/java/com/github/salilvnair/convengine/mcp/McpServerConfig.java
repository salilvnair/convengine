package com.github.salilvnair.convengine.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Persisted configuration for a single MCP (Model Context Protocol) server.
 *
 * Two transports are supported:
 *   - {@code STDIO}: spawn a subprocess ({@link #command} + {@link #args}, with
 *                    {@link #env} overrides) and exchange line-delimited
 *                    JSON-RPC on its stdin/stdout. Suited for local servers
 *                    installed via npx / uvx / executables.
 *   - {@code HTTP}:  JSON-RPC 2.0 over HTTP POST against {@link #url} with
 *                    optional static {@link #headers}. The server must return
 *                    plain JSON (not an SSE stream) for now.
 *
 * Configs are serialized to {@code ~/.convengine/mcp-servers.json} by
 * {@link McpRegistry}, so adding/editing/deleting through the REST controller
 * survives restarts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpServerConfig {

    public enum Transport { STDIO, HTTP }

    /** Stable identifier. Used in the UI as the dropdown value and referenced
     *  by workflow nodes (e.g. {@code mcp:<id>:<tool>}). */
    private String id;

    /** Human-readable name shown in the UI. */
    private String name;

    /** Which transport to use. */
    private Transport transport;

    // ---- stdio transport ----
    /** Executable to spawn (e.g. {@code npx}, {@code uvx}, absolute path). */
    private String command;
    /** CLI arguments appended after {@link #command}. */
    private List<String> args;
    /** Extra environment variables merged on top of the parent process env. */
    private Map<String, String> env;

    // ---- http transport ----
    /** HTTP endpoint URL (for JSON-RPC POST). */
    private String url;
    /** Static headers to attach to every request (auth tokens, etc.). */
    private Map<String, String> headers;
}
