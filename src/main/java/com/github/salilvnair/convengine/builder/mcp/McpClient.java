package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * High-level MCP (Model Context Protocol) client — the thing workflow code
 * actually talks to. Wraps an {@link McpTransport} and exposes the subset of
 * MCP methods the Builder Studio needs:
 *
 *   - {@link #initialize()}       — mandatory handshake; also sends the follow-up
 *                                   {@code notifications/initialized} per spec.
 *   - {@link #listTools()}        — returns the server's tool manifest (each with
 *                                   name / description / inputSchema).
 *   - {@link #callTool(String, JsonNode)} — invokes a tool by name with args.
 *   - {@link #listResources()} / {@link #readResource(String)} — best-effort.
 *   - {@link #listPrompts()}      — best-effort.
 *
 * Any server method that isn't implemented server-side surfaces as an
 * {@link McpException} (JSON-RPC error code -32601 / method not found), which
 * callers should tolerate.
 */
@Slf4j
public class McpClient implements AutoCloseable {

    /** Protocol version we advertise during initialize. This is the last widely
     *  supported revision for both Anthropic's Python/TypeScript SDKs and most
     *  community servers. Bump when broader compatibility lands. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final McpTransport transport;
    private volatile boolean initialized = false;
    private volatile JsonNode serverInfo;
    private volatile JsonNode capabilities;

    public McpClient(McpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    /** Perform the MCP handshake. Safe to call once; subsequent calls are no-ops. */
    public synchronized void initialize() {
        if (initialized) return;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "convengine-builder-studio");
        clientInfo.put("version", "1.0.0");
        params.putObject("capabilities");  // minimal: we don't advertise any client caps

        JsonNode result = transport.request("initialize", params);
        if (result != null) {
            serverInfo = result.get("serverInfo");
            capabilities = result.get("capabilities");
        }
        // Notify per spec so the server can send initial notifications.
        try { transport.notify("notifications/initialized", null); } catch (Exception ignored) {}
        initialized = true;
    }

    public JsonNode getServerInfo() { return serverInfo; }
    public JsonNode getCapabilities() { return capabilities; }

    /** {@code tools/list}. Returns a live list of {@code {name,description,inputSchema}} nodes. */
    public List<JsonNode> listTools() {
        ensureInitialized();
        JsonNode result = transport.request("tools/list", null);
        return arrayOf(result, "tools");
    }

    /** {@code tools/call}. {@code arguments} may be {@code null} for no-arg tools. */
    public JsonNode callTool(String name, JsonNode arguments) {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        if (arguments != null && !arguments.isNull()) params.set("arguments", arguments);
        else params.putObject("arguments");
        return transport.request("tools/call", params);
    }

    /** {@code resources/list}. Empty list if the server doesn't support resources. */
    public List<JsonNode> listResources() {
        ensureInitialized();
        try { return arrayOf(transport.request("resources/list", null), "resources"); }
        catch (McpException e) { return Collections.emptyList(); }
    }

    /** {@code resources/read}. */
    public JsonNode readResource(String uri) {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        return transport.request("resources/read", params);
    }

    /** {@code prompts/list}. Empty list if the server doesn't support prompts. */
    public List<JsonNode> listPrompts() {
        ensureInitialized();
        try { return arrayOf(transport.request("prompts/list", null), "prompts"); }
        catch (McpException e) { return Collections.emptyList(); }
    }

    /** {@code prompts/get}. */
    public JsonNode getPrompt(String name, Map<String, String> arguments) {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        if (arguments != null && !arguments.isEmpty()) {
            ObjectNode argNode = params.putObject("arguments");
            arguments.forEach(argNode::put);
        }
        return transport.request("prompts/get", params);
    }

    private void ensureInitialized() { if (!initialized) initialize(); }

    private List<JsonNode> arrayOf(JsonNode result, String field) {
        List<JsonNode> out = new ArrayList<>();
        if (result == null) return out;
        JsonNode arr = result.get(field);
        if (arr instanceof ArrayNode a) a.forEach(out::add);
        return out;
    }

    @Override
    public void close() { transport.close(); }

    /** Whether the underlying transport (and its process/connection) is still healthy. */
    public boolean isAlive() { return transport.isAlive(); }
}
