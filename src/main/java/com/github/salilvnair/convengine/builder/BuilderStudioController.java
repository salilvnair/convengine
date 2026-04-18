package com.github.salilvnair.convengine.builder;

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
 *
 * <h3>DTO note</h3>
 * The free-form JSON payload fields are typed as {@link Map}/{@link Object}
 * rather than Jackson's {@code JsonNode}. Spring Boot 4 binds request bodies
 * through Jackson 3 ({@code tools.jackson}), while the rest of this codebase
 * still consumes the classic {@code com.fasterxml.jackson} tree API. Using
 * {@code com.fasterxml.jackson.databind.JsonNode} here would leave the
 * Jackson 3 converter unable to instantiate it ("no Creators" error).
 * Plain {@code Map}/{@code Object} deserialize cleanly, and
 * {@link BuilderStudioRunner} re-wraps them as a fasterxml tree internally.
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
            resp.put("model", body.getAgent() == null ? null : body.getAgent().get("model"));
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

    /**
     * One-shot agent call payload. {@code agent} holds the usual keys
     * ({@code model}, {@code systemPrompt}, {@code userPrompt},
     * {@code responseFormat}, {@code strictOutput}, {@code temperature}) as
     * a plain map — Jackson 3 can bind that without touching the fasterxml
     * tree types used throughout the rest of the app.
     */
    @lombok.Data
    public static class AgentCallRequest {
        private Map<String, Object> agent;
        private String input;
    }

    /**
     * Full-graph run payload. {@code workflow} carries the canvas shape
     * {@code { nodes, edges, subBlockValues }} as a plain map, re-wrapped
     * to a fasterxml tree inside {@link BuilderStudioRunner#runGraph}.
     */
    @lombok.Data
    public static class RunRequest {
        private Map<String, Object> workflow;
        private Map<String, String> inputs;
    }
}
