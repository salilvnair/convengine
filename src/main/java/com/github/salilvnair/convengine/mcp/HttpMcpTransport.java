package com.github.salilvnair.convengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP JSON-RPC 2.0 transport for MCP (the "streamable HTTP" transport, JSON
 * response variant). Each {@link #request(String, JsonNode)} is a POST to the
 * configured URL with a JSON-RPC envelope; the server is expected to return a
 * single JSON body (not an SSE stream).
 *
 * Session affinity: after a successful {@code initialize}, MCP servers return
 * an {@code Mcp-Session-Id} response header; we echo it on every subsequent
 * request so the server can match this conversation to its state.
 */
@Slf4j
public class HttpMcpTransport implements McpTransport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final AtomicLong idSeq = new AtomicLong(1);
    private volatile String sessionId;

    public HttpMcpTransport(String url, Map<String, String> headers, ObjectMapper mapper) {
        this.mapper = mapper;
        this.endpoint = URI.create(url);
        this.headers = headers == null ? Map.of() : headers;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public JsonNode request(String method, JsonNode params) throws McpException {
        long id = idSeq.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);
        HttpResponse<String> resp = send(msg);

        // Capture MCP session id if the server provided one.
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> this.sessionId = s);

        try {
            JsonNode body = mapper.readTree(resp.body());
            JsonNode err = body.get("error");
            if (err != null && !err.isNull()) {
                throw new McpException(err.path("code").asInt(0), err.path("message").asText("MCP error"));
            }
            return body.get("result");
        } catch (McpException mce) {
            throw mce;
        } catch (Exception e) {
            throw new McpException("failed to parse MCP response: " + resp.body(), e);
        }
    }

    @Override
    public void notify(String method, JsonNode params) throws McpException {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);
        send(msg);
    }

    private HttpResponse<String> send(ObjectNode msg) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    // Accept both forms; servers may return JSON directly.
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(msg)));
            headers.forEach(rb::header);
            if (sessionId != null) rb.header("Mcp-Session-Id", sessionId);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new McpException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp;
        } catch (McpException mce) {
            throw mce;
        } catch (Exception e) {
            throw new McpException("MCP HTTP call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // HttpClient has no explicit close in JDK 17; nothing to release.
        sessionId = null;
    }
}
