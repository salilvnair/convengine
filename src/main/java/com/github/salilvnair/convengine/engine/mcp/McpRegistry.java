package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.salilvnair.convengine.entity.CeMcpServer;
import com.github.salilvnair.convengine.repo.McpServerRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of MCP connections.
 *
 * Server configs are always DB-backed ({@code ce_mcp_server}, via
 * {@link McpServerRepository}) — there is no local-file fallback. This is
 * deliberate: a file under a pod's home directory (as earlier revisions of
 * this class used, {@code ~/.convengine/mcp-servers.json}) is invisible to
 * every other replica in a horizontally-scaled deployment (AKS, k8s, etc.),
 * so it can't be the source of truth. The {@code ce_mcp_server} table can.
 *
 * Responsibilities:
 *   1. Load configs from {@code ce_mcp_server} at startup, and write through
 *      to it on every upsert/remove.
 *   2. Lazily spawn/connect an {@link McpClient} the first time a server is
 *      accessed; cache it so subsequent tool calls reuse the same process /
 *      HTTP session.
 *   3. Cache the latest {@code tools/list} result per server so the UI
 *      dropdown is instant; {@link #refresh(String)} forces a re-query.
 *   4. Periodically reload configs from the DB (every {@value
 *      #DB_REFRESH_INTERVAL_SECONDS}s) so a server registered/edited/removed
 *      on one replica is picked up by the others within one refresh cycle,
 *      without any direct pod-to-pod communication.
 *   5. Close all live connections on shutdown (triggered by
 *      {@link PreDestroy}).
 *
 * Requires the {@code ce_mcp_server} table to exist (see {@code ddl.sql} /
 * {@code ddl_postgres.sql}, or the standalone additive migration for
 * existing databases). Startup does not hard-fail if the table is missing —
 * it logs an error and starts with zero configs so the rest of the app can
 * still boot — but registering/listing/calling MCP servers won't work until
 * the table is created.
 */
@Slf4j
@Service
public class McpRegistry {

    private static final long DB_REFRESH_INTERVAL_SECONDS = 30;

    /** JSON-RPC is single-line newline-delimited — indentation would break
     *  Python's mcp.server.stdio parser, so this is always a non-indented
     *  copy regardless of how the Spring-managed mapper is configured. */
    private final ObjectMapper mapper;
    private final McpServerRepository serverRepository;

    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> toolCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService refreshExecutor;

    public McpRegistry(ObjectMapper mapper, McpServerRepository serverRepository) {
        this.mapper = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.serverRepository = serverRepository;
        load();
    }

    /** Starts the periodic DB-refresh loop. Split out from the constructor
     *  since a background executor has no business running for a plain
     *  {@code new McpRegistry(...)} that isn't actually a live Spring bean
     *  (e.g. in tests). */
    @PostConstruct
    void init() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-registry-db-refresh");
            t.setDaemon(true);
            return t;
        });
        refreshExecutor.scheduleWithFixedDelay(this::refreshFromDbQuietly,
                DB_REFRESH_INTERVAL_SECONDS, DB_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // ---- public API used by the controller ----

    public List<McpServerConfig> list() {
        List<McpServerConfig> out = new ArrayList<>(configs.values());
        out.sort((a, b) -> a.getName() == null ? 1 : a.getName().compareToIgnoreCase(b.getName() == null ? "" : b.getName()));
        return out;
    }

    public Optional<McpServerConfig> get(String id) { return Optional.ofNullable(configs.get(id)); }

    /** Create-or-replace. If a client for this id was already running, close it
     *  so the next tool call picks up the new config. Writes through to
     *  {@code ce_mcp_server} — exceptions propagate to the caller (the REST
     *  controller turns them into a 400 with the error message). */
    public synchronized McpServerConfig upsert(McpServerConfig cfg) {
        if (cfg.getId() == null || cfg.getId().isBlank()) {
            cfg.setId("srv_" + Long.toHexString(System.nanoTime()));
        }
        serverRepository.save(toEntity(cfg));
        configs.put(cfg.getId(), cfg);
        closeClient(cfg.getId());
        toolCache.remove(cfg.getId());
        return cfg;
    }

    public synchronized void remove(String id) {
        serverRepository.deleteById(id);
        configs.remove(id);
        closeClient(id);
        toolCache.remove(id);
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
        // Check if existing client's process is still alive; if not, discard it
        // and respawn (handles pkill, crash, manual kill).
        McpClient existing = clients.get(id);
        if (existing != null && !existing.isAlive()) {
            log.info("MCP server '{}' process died — respawning...", id);
            existing.close();
            clients.remove(id);
            toolCache.remove(id);
        }
        return clients.computeIfAbsent(id, key -> {
            McpServerConfig cfg = configs.get(key);
            if (cfg == null) throw new McpException("unknown MCP server: " + key);
            log.info("Starting MCP server '{}' ({} {})...", cfg.getName(), cfg.getCommand(), cfg.getArgs());
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
            case SSE -> {
                if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                    throw new McpException("sse MCP server needs a 'url'");
                }
                yield new SseMcpTransport(cfg.getUrl(), cfg.getHeaders(), mapper);
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
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        clients.values().forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
        clients.clear();
    }

    // ---- persistence ----

    private void load() {
        try {
            List<CeMcpServer> rows = serverRepository.findAll();
            Map<String, McpServerConfig> keyed = new LinkedHashMap<>();
            for (CeMcpServer row : rows) {
                McpServerConfig cfg = fromEntity(row);
                if (cfg.getId() != null) keyed.put(cfg.getId(), cfg);
            }
            configs.clear();
            configs.putAll(keyed);
            log.info("Loaded {} MCP server config(s) from ce_mcp_server", configs.size());
        } catch (Exception e) {
            log.error("Could not load ce_mcp_server — MCP server registration/discovery won't work until "
                    + "this table exists and is reachable (run the ce_mcp_server DDL): {}", e.getMessage());
        }
    }

    /** Periodic reconciliation so other replicas' writes become visible here.
     *  Only touches {@code configs}; closes clients for configs that were
     *  removed or changed so the next call picks up fresh settings. */
    private void refreshFromDbQuietly() {
        try {
            List<CeMcpServer> rows = serverRepository.findAll();
            Map<String, McpServerConfig> latest = new LinkedHashMap<>();
            for (CeMcpServer row : rows) {
                McpServerConfig cfg = fromEntity(row);
                if (cfg.getId() != null) latest.put(cfg.getId(), cfg);
            }
            for (String existingId : new ArrayList<>(configs.keySet())) {
                McpServerConfig latestCfg = latest.get(existingId);
                if (latestCfg == null || !latestCfg.equals(configs.get(existingId))) {
                    closeClient(existingId);
                    toolCache.remove(existingId);
                }
            }
            configs.keySet().retainAll(latest.keySet());
            configs.putAll(latest);
        } catch (Exception e) {
            log.debug("MCP DB refresh skipped: {}", e.getMessage());
        }
    }

    // ---- entity <-> DTO mapping ----

    private static McpServerConfig fromEntity(CeMcpServer row) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setId(row.getId());
        cfg.setName(row.getName());
        cfg.setTransport(row.getTransport() == null ? null : McpServerConfig.Transport.valueOf(row.getTransport()));
        cfg.setCommand(row.getCommand());
        cfg.setArgs(row.getArgs());
        cfg.setEnv(row.getEnv());
        cfg.setUrl(row.getUrl());
        cfg.setHeaders(row.getHeaders());
        return cfg;
    }

    private static CeMcpServer toEntity(McpServerConfig cfg) {
        CeMcpServer row = new CeMcpServer();
        row.setId(cfg.getId());
        row.setName(cfg.getName());
        row.setTransport(cfg.getTransport() == null ? null : cfg.getTransport().name());
        row.setCommand(cfg.getCommand());
        row.setArgs(cfg.getArgs());
        row.setEnv(cfg.getEnv());
        row.setUrl(cfg.getUrl());
        row.setHeaders(cfg.getHeaders());
        row.setEnabled(true);
        OffsetDateTime now = OffsetDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** Convenience: expose known server ids for ops / health checks. */
    public List<String> liveClientIds() { return new ArrayList<>(clients.keySet()); }

    /** Convenience: expose the cached tool map (read-only). */
    public Map<String, List<JsonNode>> cachedTools() { return Collections.unmodifiableMap(toolCache); }

    /**
     * Returns all tools discovered from all connected MCP servers.
     * Uses the cached tool manifest per server; does NOT reconnect or refresh.
     * Call {@link #refresh(String)} first if you need a fresh manifest.
     */
    public List<McpDiscoveredTool> discoveredTools() {
        List<McpDiscoveredTool> result = new ArrayList<>();
        for (McpServerConfig cfg : configs.values()) {
            String serverId = cfg.getId();
            List<JsonNode> tools = toolCache.get(serverId);
            if (tools == null) {
                try {
                    tools = refresh(serverId);
                } catch (Exception ignored) {
                    continue;
                }
            }
            String serverName = cfg.getName() != null ? cfg.getName() : serverId;
            for (JsonNode tool : tools) {
                String toolName = tool.path("name").asText(null);
                if (toolName == null || toolName.isBlank()) continue;
                String description = tool.path("description").asText(
                        "Tool '" + toolName + "' on MCP server '" + serverName + "'");
                result.add(new McpDiscoveredTool(
                        McpDiscoveredTool.buildToolCode(serverId, toolName),
                        serverId,
                        toolName,
                        description));
            }
        }
        return result;
    }
}
