package com.github.salilvnair.convengine.builder;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST entry point for the Agent Builder Studio.
 *
 * Exposes two endpoints:
 *
 *  - POST /api/v1/builder-studio/agent  → one-shot agent call. The browser
 *    sends the system prompt, user prompt, model, etc., and the server calls
 *    {@code LlmClient.generateText(...)} (or {@code generateJson} when a
 *    response schema is provided). This is what the client-side graph
 *    runner calls for every `agent` node.
 *
 *  - POST /api/v1/builder-studio/run    → full-graph run. The UI posts the
 *    whole canvas plus collected user inputs. The server walks the graph,
 *    executes `agent` nodes against the LLM, and returns a trace. Provided
 *    for clients that want the server to orchestrate instead of the browser.
 *
 * Neither endpoint goes through the intent / MCP / semantic-query pipeline
 * — the builder studio is a direct graph runner, not a conversation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/builder-studio")
@RequiredArgsConstructor
public class BuilderStudioController {

    private final BuilderStudioRunner runner;

    @PostMapping("/agent")
    public ResponseEntity<Map<String, Object>> runAgent(@RequestBody AgentCallRequest body) {
        try {
            long t0 = System.currentTimeMillis();
            String output = runner.callAgent(body.getAgent(), body.getInput());
            Map<String, Object> resp = new HashMap<>();
            resp.put("output", output);
            resp.put("model", body.getAgent() == null ? null : body.getAgent().path("model").asText(null));
            resp.put("ms", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("builder-studio agent call failed", e);
            Map<String, Object> err = new HashMap<>();
            err.put("output", null);
            err.put("error", e.getMessage());
            return ResponseEntity.ok(err);
        }
    }

    @PostMapping("/run")
    public ResponseEntity<RunResponse> runWorkflow(@RequestBody RunRequest body) {
        try {
            RunResponse resp = runner.runGraph(body);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("builder-studio run failed", e);
            RunResponse err = new RunResponse();
            err.setError(e.getMessage());
            return ResponseEntity.ok(err);
        }
    }

    // ---- DTOs kept inline for a compact footprint ----

    @lombok.Data
    public static class AgentCallRequest {
        private JsonNode agent;   // { model, systemPrompt, userPrompt, responseFormat, temperature }
        private String input;
    }

    @lombok.Data
    public static class RunRequest {
        private JsonNode workflow;           // { nodes, edges, subBlockValues }
        private Map<String, String> inputs;  // user_input node id -> value
    }
}
