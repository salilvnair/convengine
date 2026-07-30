package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.entity.CeMcpServer;
import com.github.salilvnair.convengine.repo.McpServerRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpRegistry is always DB-backed (ce_mcp_server via McpServerRepository) —
 * there is no local-file fallback. These tests use a Mockito mock of
 * McpServerRepository backed by a real in-memory map so findAll/save/
 * deleteById behave like a real repository across a test's sequence of
 * calls, without touching an actual database.
 */
class McpRegistryTest {

    /** Backing map is exposed so a few tests can assert against it directly
     *  (e.g. confirming a write actually reached "the DB"). */
    private static final class InMemoryRepo {
        final Map<String, CeMcpServer> store = new ConcurrentHashMap<>();
        final McpServerRepository mock = mock(McpServerRepository.class);

        InMemoryRepo() {
            when(mock.findAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
            when(mock.save(any(CeMcpServer.class))).thenAnswer(inv -> {
                CeMcpServer row = inv.getArgument(0);
                store.put(row.getId(), row);
                return row;
            });
            doAnswer(inv -> {
                store.remove(inv.getArgument(0, String.class));
                return null;
            }).when(mock).deleteById(anyString());
        }
    }

    private McpRegistry newRegistry() {
        return new McpRegistry(new ObjectMapper(), new InMemoryRepo().mock);
    }

    private CeMcpServer row(String id, String name) {
        CeMcpServer row = new CeMcpServer();
        row.setId(id);
        row.setName(name);
        row.setTransport("HTTP");
        row.setUrl("http://localhost:1234");
        row.setArgs(List.of("--verbose"));
        row.setEnv(Map.of("TOKEN", "abc"));
        row.setEnabled(true);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }

    @Test
    void upsertAssignsGeneratedIdWhenMissing() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .name("no id server")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:1234")
                .build();

        McpServerConfig saved = registry.upsert(cfg);
        assertNotNull(saved.getId());
        assertTrue(saved.getId().startsWith("srv_"));
    }

    @Test
    void upsertGetListRemoveRoundTrip() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-crud")
                .name("crud server")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:5555")
                .build();

        registry.upsert(cfg);
        assertTrue(registry.get("srv-crud").isPresent());
        assertEquals("crud server", registry.get("srv-crud").get().getName());
        assertTrue(registry.list().stream().anyMatch(c -> "srv-crud".equals(c.getId())));

        registry.remove("srv-crud");
        assertFalse(registry.get("srv-crud").isPresent());
        assertFalse(registry.list().stream().anyMatch(c -> "srv-crud".equals(c.getId())));
    }

    @Test
    void upsertWritesThroughToRepository() {
        InMemoryRepo repo = new InMemoryRepo();
        McpRegistry registry = new McpRegistry(new ObjectMapper(), repo.mock);
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-persist")
                .name("persisted")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:6000")
                .build();

        registry.upsert(cfg);

        verify(repo.mock).save(any(CeMcpServer.class));
        assertTrue(repo.store.containsKey("srv-persist"));
        assertEquals("persisted", repo.store.get("srv-persist").getName());
    }

    @Test
    void removeDeletesFromRepository() {
        InMemoryRepo repo = new InMemoryRepo();
        McpRegistry registry = new McpRegistry(new ObjectMapper(), repo.mock);
        registry.upsert(McpServerConfig.builder().id("srv-remove").name("to remove")
                .transport(McpServerConfig.Transport.HTTP).url("http://localhost:1").build());

        registry.remove("srv-remove");

        verify(repo.mock).deleteById("srv-remove");
        assertFalse(repo.store.containsKey("srv-remove"));
    }

    @Test
    void upsertPropagatesRepositoryFailureToCaller() {
        McpServerRepository repo = mock(McpServerRepository.class);
        doThrow(new RuntimeException("relation \"ce_mcp_server\" does not exist"))
                .when(repo).save(any(CeMcpServer.class));
        McpRegistry registry = new McpRegistry(new ObjectMapper(), repo);

        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-fails")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:1")
                .build();

        assertThrows(RuntimeException.class, () -> registry.upsert(cfg));
    }

    @Test
    void loadPopulatesConfigsFromRepositoryAtStartupWithFullFieldFidelity() {
        InMemoryRepo repo = new InMemoryRepo();
        repo.store.put("db-srv", row("db-srv", "DB Server"));

        McpRegistry registry = new McpRegistry(new ObjectMapper(), repo.mock);

        Optional<McpServerConfig> found = registry.get("db-srv");
        assertTrue(found.isPresent());
        assertEquals("DB Server", found.get().getName());
        assertEquals(McpServerConfig.Transport.HTTP, found.get().getTransport());
        assertEquals("http://localhost:1234", found.get().getUrl());
        assertEquals(List.of("--verbose"), found.get().getArgs());
        assertEquals("abc", found.get().getEnv().get("TOKEN"));
    }

    @Test
    void loadHandlesRepositoryFailureWithoutThrowing() {
        McpServerRepository repo = mock(McpServerRepository.class);
        when(repo.findAll()).thenThrow(new RuntimeException("no datasource"));

        // Construction itself must not throw even though the initial DB read fails.
        McpRegistry registry = new McpRegistry(new ObjectMapper(), repo);

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void buildTransportThrowsForStdioWithoutCommand() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-stdio-nocmd")
                .transport(McpServerConfig.Transport.STDIO)
                .build();
        registry.upsert(cfg);

        McpException ex = assertThrows(McpException.class, () -> registry.listTools("srv-stdio-nocmd"));
        assertTrue(ex.getMessage().contains("command"));
    }

    @Test
    void buildTransportThrowsForHttpWithoutUrl() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-http-nourl")
                .transport(McpServerConfig.Transport.HTTP)
                .build();
        registry.upsert(cfg);

        McpException ex = assertThrows(McpException.class, () -> registry.listTools("srv-http-nourl"));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void buildTransportThrowsForSseWithoutUrl() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-sse-nourl")
                .transport(McpServerConfig.Transport.SSE)
                .build();
        registry.upsert(cfg);

        McpException ex = assertThrows(McpException.class, () -> registry.listTools("srv-sse-nourl"));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void buildTransportThrowsWhenTransportUnset() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-no-transport")
                .build();
        registry.upsert(cfg);

        McpException ex = assertThrows(McpException.class, () -> registry.listTools("srv-no-transport"));
        assertTrue(ex.getMessage().contains("transport"));
    }

    @Test
    void discoveredToolsSkipsServersThatFailToConnect() {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-unreachable")
                .name("unreachable")
                .transport(McpServerConfig.Transport.STDIO)
                .build(); // no command -> buildTransport() throws -> refresh() throws -> discoveredTools() should skip it

        registry.upsert(cfg);

        List<McpDiscoveredTool> tools = registry.discoveredTools();
        assertTrue(tools.stream().noneMatch(t -> "srv-unreachable".equals(t.serverId())));
    }

    @Test
    void discoveredToolsMergesCachedToolsIntoToolCodes() throws Exception {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-cached")
                .name("cached server")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:9999")
                .build();
        registry.upsert(cfg);

        // Seed the private toolCache directly so discoveredTools() doesn't need a
        // live connection — it should skip refresh() when a cache entry is present.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode toolNode = mapper.createObjectNode()
                .put("name", "search")
                .put("description", "Searches things");
        seedToolCache(registry, "srv-cached", List.of(toolNode));

        List<McpDiscoveredTool> discovered = registry.discoveredTools();
        McpDiscoveredTool match = discovered.stream()
                .filter(t -> "srv-cached".equals(t.serverId()))
                .findFirst()
                .orElseThrow();

        assertEquals("mcp.srv-cached.search", match.toolCode());
        assertEquals("search", match.toolName());
        assertEquals("Searches things", match.description());
    }

    @Test
    void discoveredToolsUsesDefaultDescriptionWhenServerOmitsOne() throws Exception {
        McpRegistry registry = newRegistry();
        McpServerConfig cfg = McpServerConfig.builder()
                .id("srv-nodesc")
                .name("nodesc server")
                .transport(McpServerConfig.Transport.HTTP)
                .url("http://localhost:9998")
                .build();
        registry.upsert(cfg);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode toolNode = mapper.createObjectNode().put("name", "ping");
        seedToolCache(registry, "srv-nodesc", List.of(toolNode));

        List<McpDiscoveredTool> discovered = registry.discoveredTools();
        McpDiscoveredTool match = discovered.stream()
                .filter(t -> "srv-nodesc".equals(t.serverId()))
                .findFirst()
                .orElseThrow();

        assertTrue(match.description().contains("ping"));
        assertTrue(match.description().contains("nodesc server"));
    }

    @SuppressWarnings("unchecked")
    private void seedToolCache(McpRegistry registry, String serverId, List<JsonNode> tools) throws Exception {
        Field field = McpRegistry.class.getDeclaredField("toolCache");
        field.setAccessible(true);
        Map<String, List<JsonNode>> cache = (Map<String, List<JsonNode>>) field.get(registry);
        cache.put(serverId, tools);
    }
}
