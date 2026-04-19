package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stdio JSON-RPC 2.0 transport for MCP.
 *
 * Spawns a subprocess ({@code command + args}), writes one JSON object per line
 * to its stdin, and reads one JSON object per line from its stdout. A single
 * reader thread pumps stdout, routing responses by {@code id} into pending
 * {@link CompletableFuture}s; non-response messages (server-initiated
 * notifications or requests) are logged and ignored. stderr is drained on its
 * own thread and forwarded to the log at DEBUG.
 *
 * Thread-safety: {@link #request(String, JsonNode)} and {@link #notify} may be
 * called concurrently; writes are synchronized on a private monitor. Request
 * correlation uses an {@link AtomicLong} counter.
 *
 * This is a deliberately minimal implementation — no reconnect, no flow
 * control, no SSE. Good enough for the Builder Studio's "configure server →
 * list tools → call tool" loop.
 */
@Slf4j
public class StdioMcpTransport implements McpTransport {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final ObjectMapper mapper;
    private final Process process;
    private final Writer stdin;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);
    private final Object writeLock = new Object();
    private volatile boolean closed = false;

    public StdioMcpTransport(String command, List<String> args, Map<String, String> env, ObjectMapper mapper) {
        this.mapper = mapper;
        try {
            ProcessBuilder pb = new ProcessBuilder();
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(command);
            if (args != null) cmd.addAll(args);
            pb.command(cmd);
            if (env != null) pb.environment().putAll(env);
            pb.redirectErrorStream(false);
            this.process = pb.start();
        } catch (IOException e) {
            throw new McpException("failed to spawn MCP process: " + command, e);
        }
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

        this.readerThread = new Thread(this::pumpStdout, "mcp-stdio-reader-" + process.pid());
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::pumpStderr, "mcp-stdio-stderr-" + process.pid());
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    @Override
    public JsonNode request(String method, JsonNode params) throws McpException {
        if (closed) throw new McpException("transport closed");
        long id = idSeq.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            writeMessage(msg);
            return fut.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            pending.remove(id);
            throw new McpException("MCP request '" + method + "' timed out after " + DEFAULT_TIMEOUT_MS + "ms");
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof McpException me) throw me;
            throw new McpException("MCP request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void notify(String method, JsonNode params) throws McpException {
        if (closed) throw new McpException("transport closed");
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null && !params.isNull()) msg.set("params", params);
        writeMessage(msg);
    }

    private void writeMessage(ObjectNode msg) {
        String line;
        try {
            line = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new McpException("failed to serialize MCP message", e);
        }
        synchronized (writeLock) {
            try {
                stdin.write(line);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new McpException("failed to write to MCP stdin", e);
            }
        }
    }

    private void pumpStdout() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode msg = mapper.readTree(line);
                    JsonNode idNode = msg.get("id");
                    if (idNode == null || idNode.isNull()) {
                        // Server-initiated request/notification — ignore for now.
                        log.debug("MCP stdio inbound notification: {}", msg.path("method").asText(""));
                        continue;
                    }
                    long id = idNode.asLong(-1);
                    CompletableFuture<JsonNode> fut = pending.remove(id);
                    if (fut == null) {
                        log.debug("MCP stdio response with unknown id {}", id);
                        continue;
                    }
                    JsonNode err = msg.get("error");
                    if (err != null && !err.isNull()) {
                        int code = err.path("code").asInt(0);
                        String m = err.path("message").asText("MCP error");
                        fut.completeExceptionally(new McpException(code, m));
                    } else {
                        fut.complete(msg.get("result"));
                    }
                } catch (Exception e) {
                    log.warn("MCP stdio reader: malformed message '{}' — {}", line, e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) log.warn("MCP stdio reader ended: {}", e.getMessage());
        } finally {
            // Fail any still-pending requests so callers don't hang forever.
            pending.forEach((id, f) -> f.completeExceptionally(new McpException("MCP process exited")));
            pending.clear();
        }
    }

    private void pumpStderr() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.debug("MCP[stderr]: {}", line);
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { stdin.close(); } catch (IOException ignored) {}
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        pending.forEach((id, f) -> f.completeExceptionally(new McpException("transport closed")));
        pending.clear();
    }
}
