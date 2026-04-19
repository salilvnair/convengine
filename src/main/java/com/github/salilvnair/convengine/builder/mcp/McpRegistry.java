package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of MCP connections used by the Builder Studio.
 *
 * Responsibilities:
 *   1. Persist server configs across JVM restarts in
 *      {@code ~/.convengine/mcp-servers.json} (human-editable JSON array).
 *   2. Lazily spawn/connect an {@link McpClient} the first time a server is
 *      accessed; cache it so subsequent tool calls reuse the same process /
 *      HTTP session.
 *   3. Cache the latest {@code tools/list} result per server so the UI
 *      dropdown is instant; {@link #refresh(String)} forces a re-query.
 *   4. Close all live connections on shutdown (triggered by
 *      {@link PreDestroy}).
 *
 * Everything here is stored in memory keyed by {@link McpServerConfig}'s id.
 * We intentionally don't use a database — MCP server configs are tiny and
 * rarely change.
 */
@Slf4j
@Service
public class McpRegistry {

    private static final Path STORE_PATH =
            Paths.get(System.getProperty("user.home"), ".convengine", "mcp-servers.json");

    private final ObjectMapper mapper;
    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> toolCache = new ConcurrentHashMap<>();

    public McpRegistry(ObjectMapper mapper) {
        // Reuse the Spring-managed mapper but enable indentation for the
        // persisted file so users can edit it by hand.
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    // ---- public API used by the controller ----

    public List<McpServerConfig> list() {
        List<McpServerConfig> out = new ArrayList<>(configs.values());
        out.sort((a, b) -> a.getName() == null ? 1 : a.getName().compareToIgnoreCase(b.getName() == null ? "" : b.getName()));
        return out;
    }

    public Optional<McpServerConfig> get(String id) { return Optional.ofNullable(configs.get(id)); }

    /** Create-or-replace. If a client for this id was already running, close it
     *  so the next tool call picks up the new config. */
    public synchronized McpServerConfig upsert(McpServerConfig cfg) {
        if (cfg.getId() == null || cfg.getId().isBlank()) {
            cfg.setId("srv_" + Long.toHexString(System.nanoTime()));
        }
        configs.put(cfg.getId(), cfg);
        closeClient(cfg.getId());
        toolCache.remove(cfg.getId());
        save();
        return cfg;
    }

    public synchronized void remove(String id) {
        configs.remove(id);
        closeClient(id);
        toolCache.remove(id);
        save();
    }

    /** Fetch (and cache) the server's tool manifest. */
    public List<JsonNode> listTools(String id) {
        List<JsonNode> cached = toolCache.get(id);
        if (cached != null) return cached;
        return refresh(id);
    }

    /** Force a re-query of {@code tools/list} for this server. */
    public List<JsonNode> refresh(String id) {
        McpClient client = client(id);
        List<JsonNode> tools = client.listTools();
        toolCache.put(id, tools);
        return tools;
    }

    /** Invoke a tool. {@code arguments} can be {@code null} for no-arg tools. */
    public JsonNode callTool(String id, String toolName, JsonNode arguments) {
        return client(id).callTool(toolName, arguments);
    }

    // ---- internals ----

    private McpClient client(String id) {
        return clients.computeIfAbsent(id, key -> {
            McpServerConfig cfg = configs.get(key);
            if (cfg == null) throw new McpException("unknown MCP server: " + key);
            McpTransport transport = buildTransport(cfg);
            McpClient c = new McpClient(transport, mapper);
            try {
                c.initialize();
            } catch (RuntimeException e) {
                c.close();
                throw e;
            }
            return c;
        });
    }

    private McpTransport buildTransport(McpServerConfig cfg) {
        if (cfg.getTransport() == null) {
            throw new McpException("MCP server '" + cfg.getId() + "' has no transport set");
        }
        return switch (cfg.getTransport()) {
            case STDIO -> {
                if (cfg.getCommand() == null || cfg.getCommand().isBlank()) {
                    throw new McpException("stdio MCP server needs a 'command'");
                }
                yield new StdioMcpTransport(cfg.getCommand(), cfg.getArgs(), cfg.getEnv(), mapper);
            }
            case HTTP -> {
                if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                    throw new McpException("http MCP server needs a 'url'");
                }
                yield new HttpMcpTransport(cfg.getUrl(), cfg.getHeaders(), mapper);
            }
        };
    }

    private void closeClient(String id) {
        McpClient c = clients.remove(id);
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        clients.values().forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
        clients.clear();
    }

    // ---- persistence ----

    private void load() {
        try {
            if (!Files.exists(STORE_PATH)) return;
            byte[] bytes = Files.readAllBytes(STORE_PATH);
            if (bytes.length == 0) return;
            List<McpServerConfig> list = mapper.readValue(bytes, new TypeReference<List<McpServerConfig>>() {});
            Map<String, McpServerConfig> keyed = new LinkedHashMap<>();
            for (McpServerConfig c : list) if (c.getId() != null) keyed.put(c.getId(), c);
            configs.putAll(keyed);
            log.info("Loaded {} MCP server config(s) from {}", configs.size(), STORE_PATH);
        } catch (Exception e) {
            log.warn("Failed to load {}: {}", STORE_PATH, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            File tmp = new File(STORE_PATH.getParent().toFile(), STORE_PATH.getFileName() + ".tmp");
            mapper.writeValue(tmp, list());
            Files.move(tmp.toPath(), STORE_PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Failed to persist {}: {}", STORE_PATH, e.getMessage());
        }
    }

    /** Convenience: expose known server ids for ops / health checks. */
    public List<String> liveClientIds() { return new ArrayList<>(clients.keySet()); }

    /** Convenience: expose the cached tool map (read-only). */
    public Map<String, List<JsonNode>> cachedTools() { return Collections.unmodifiableMap(toolCache); }
}
