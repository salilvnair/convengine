package com.github.salilvnair.convengine.builder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the Agent Builder Studio's MCP integration.
 *
 *   GET    /api/v1/mcp/servers                     — list configured servers
 *   POST   /api/v1/mcp/servers                     — add / update a server
 *   DELETE /api/v1/mcp/servers/{id}                — remove one
 *   GET    /api/v1/mcp/servers/{id}/tools          — list that server's tools
 *                                                    (cached; ?refresh=true to re-query)
 *   POST   /api/v1/mcp/servers/{id}/tools/{tool}/call
 *          body: { "arguments": {...} }             — invoke a tool
 *
 * All errors are surfaced as HTTP 400 with a {@code { error: "..." }} body so
 * the UI can show a single consistent error path.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpRegistry registry;

    @GetMapping("/servers")
    public List<McpServerConfig> listServers() {
        return registry.list();
    }

    @PostMapping("/servers")
    public ResponseEntity<?> upsertServer(@RequestBody McpServerConfig body) {
        try {
            return ResponseEntity.ok(registry.upsert(body));
        } catch (Exception e) {
            log.warn("MCP upsert failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable String id) {
        registry.remove(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/servers/{id}/tools")
    public ResponseEntity<?> listTools(@PathVariable String id,
                                       @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        try {
            List<JsonNode> tools = refresh ? registry.refresh(id) : registry.listTools(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("serverId", id);
            resp.put("tools", tools);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("MCP listTools({}) failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/servers/{id}/tools/{tool}/call")
    public ResponseEntity<?> callTool(@PathVariable String id,
                                      @PathVariable String tool,
                                      @RequestBody(required = false) CallBody body) {
        try {
            long t0 = System.currentTimeMillis();
            JsonNode args = body == null ? null : body.getArguments();
            JsonNode result = registry.callTool(id, tool, args);
            Map<String, Object> resp = new HashMap<>();
            resp.put("serverId", id);
            resp.put("tool", tool);
            resp.put("result", result);
            resp.put("ms", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("MCP callTool({}/{}) failed: {}", id, tool, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @lombok.Data
    public static class CallBody {
        private JsonNode arguments;
    }
}
