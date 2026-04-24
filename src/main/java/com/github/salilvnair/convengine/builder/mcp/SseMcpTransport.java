package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE (Server-Sent Events) JSON-RPC 2.0 transport for MCP.
 *
 * Protocol flow:
 *  1. Opens a persistent {@code GET /sse} connection (streamed response).
 *  2. Reads the {@code endpoint} SSE event — the server sends the POST URL
 *     for this session (e.g. {@code /messages?sessionId=abc}).
 *  3. Each {@link #request} call POSTs a JSON-RPC envelope to that URL.
 *  4. The server pushes the response as a {@code message} SSE event on the
 *     open stream; the reader thread routes it to the waiting
 *     {@link CompletableFuture} by {@code id}.
 *
 * The SSE endpoint is derived by appending {@code /sse} to the configured
 * URL unless it already ends with {@code /sse}.
 *
 * Thread-safety: {@link #request} may be called from any thread; the reader
 * thread is started in the constructor and runs until {@link #close} is
 * called. Writes are serialised through a private lock.
 */
@Slf4j
public class SseMcpTransport implements McpTransport {

    private static final Duration CONNECT_TIMEOUT  = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(30);
    private static final long    ENDPOINT_WAIT_MS  = 10_000;

    private final ObjectMapper mapper;
    private final URI sseEndpoint;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final AtomicLong idSeq = new AtomicLong(1);

    /** Resolved POST URL, populated by the reader thread from the {@code endpoint} event. */
    private volatile URI messageUrl;
    private final CompletableFuture<URI> messageUrlFuture = new CompletableFuture<>();

    /** Pending requests: correlationId → future resolved by reader thread. */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private final Thread readerThread;
    private volatile boolean closed = false;

    public SseMcpTransport(String url, Map<String, String> headers, ObjectMapper mapper) {
        this.mapper  = mapper;
        this.headers = headers == null ? Map.of() : headers;
        this.http    = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

        // Normalise SSE URL
        String sseUrl = url.matches(".*\\/sse\\/?$") ? url : (url.endsWith("/") ? url + "sse" : url + "/sse");
        this.sseEndpoint = URI.create(sseUrl);

        this.readerThread = new Thread(this::readSseStream, "mcp-sse-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    // ── McpTransport implementation ──────────────────────────────────────────

    @Override
    public JsonNode request(String method, JsonNode params) throws McpException {
        long id = idSeq.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);

        // Wait for endpoint URL (blocks until the SSE stream sends it)
        URI postUrl = awaitMessageUrl();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            String body = mapper.writeValueAsString(msg);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(postUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT);
            headers.forEach(reqBuilder::header);

            HttpResponse<String> res = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                pending.remove(id);
                throw new McpException("SSE POST returned HTTP " + res.statusCode() + ": " + res.body());
            }
        } catch (McpException mce) {
            throw mce;
        } catch (Exception e) {
            pending.remove(id);
            throw new McpException("SSE POST failed for method '" + method + "': " + e.getMessage(), e);
        }

        // Response arrives asynchronously on the SSE stream
        try {
            JsonNode result = future.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (result == null) throw new McpException("null result for method: " + method);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            throw new McpException("SSE response timeout for method: " + method);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new McpException("SSE request failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("SSE request interrupted for method: " + method);
        }
    }

    @Override
    public void notify(String method, JsonNode params) throws McpException {
        URI postUrl = awaitMessageUrl();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);
        try {
            String body = mapper.writeValueAsString(msg);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(postUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT);
            headers.forEach(reqBuilder::header);
            http.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new McpException("SSE notify failed for '" + method + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closed = true;
        messageUrlFuture.cancel(true);
        readerThread.interrupt();
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.completeExceptionally(new McpException("SSE transport closed"));
        }
        pending.clear();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private URI awaitMessageUrl() throws McpException {
        if (messageUrl != null) return messageUrl;
        try {
            return messageUrlFuture.get(ENDPOINT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new McpException("SSE endpoint event not received within " + ENDPOINT_WAIT_MS + "ms");
        } catch (Exception e) {
            throw new McpException("SSE endpoint wait failed: " + e.getMessage(), e);
        }
    }

    private void readSseStream() {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(sseEndpoint)
                .GET()
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        headers.forEach(reqBuilder::header);

        try {
            HttpResponse<java.io.InputStream> res = http.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (res.statusCode() >= 400) {
                messageUrlFuture.completeExceptionally(
                        new McpException("SSE connect HTTP " + res.statusCode()));
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.body()))) {
                String eventType = "message";
                StringBuilder dataBuilder = new StringBuilder();

                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuilder.append(line.substring(5).trim());
                    } else if (line.isEmpty()) {
                        // Blank line = end of event
                        String data = dataBuilder.toString();
                        dataBuilder.setLength(0);

                        if ("endpoint".equals(eventType) && !data.isEmpty()) {
                            try {
                                URI resolved = sseEndpoint.resolve(data);
                                messageUrl = resolved;
                                messageUrlFuture.complete(resolved);
                                log.debug("[mcp-sse] endpoint resolved: {}", resolved);
                            } catch (Exception e) {
                                log.warn("[mcp-sse] invalid endpoint data: {}", data);
                            }
                        } else if ("message".equals(eventType) && !data.isEmpty()) {
                            dispatch(data);
                        }

                        eventType = "message"; // reset for next event
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!closed) {
                log.warn("[mcp-sse] SSE reader error: {}", e.getMessage());
            }
        } finally {
            messageUrlFuture.completeExceptionally(new McpException("SSE stream ended"));
            for (CompletableFuture<JsonNode> f : pending.values()) {
                f.completeExceptionally(new McpException("SSE stream ended"));
            }
            pending.clear();
        }
    }

    private void dispatch(String data) {
        try {
            JsonNode body = mapper.readTree(data);
            JsonNode idNode = body.get("id");
            if (idNode == null || idNode.isNull()) return;

            long id = idNode.longValue();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) return;

            JsonNode error = body.get("error");
            if (error != null && !error.isNull()) {
                future.completeExceptionally(new McpException(
                        error.path("code").asInt(0),
                        error.path("message").asText("MCP SSE error")
                ));
            } else {
                future.complete(body.get("result"));
            }
        } catch (Exception e) {
            log.warn("[mcp-sse] failed to parse SSE message: {}", e.getMessage());
        }
    }
}
