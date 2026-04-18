package com.github.salilvnair.convengine.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stateless graph runner for the Agent Builder Studio.
 *
 * Given a workflow JSON of the shape:
 *   {
 *     "nodes": [ { "id", "data": { "blockType" } }, ... ],
 *     "edges": [ { "source", "target" }, ... ],
 *     "subBlockValues": { nodeId: { ... } }
 *   }
 * and a map of `user_input` node id -> typed-in value, it walks the DAG and
 * dispatches each `agent` node to {@link LlmClient}. Sibling branches (all
 * ready nodes at a level) run in parallel via a shared executor.
 *
 * This is intentionally divorced from the conversation / intent / MCP
 * pipeline; builder studio runs are direct LLM invocations only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuilderStudioRunner {

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /**
     * One-shot agent call — used by {@code POST /builder-studio/agent} from
     * the client-side graph runner. The browser is orchestrating; we just
     * invoke the LLM with the supplied prompts.
     *
     * <p>The controller hands us a plain {@link Map} (see the class javadoc
     * on {@link BuilderStudioController} for why); we re-wrap it to a
     * fasterxml tree so the rest of this class can keep using the existing
     * {@code path(...)} / {@code asText(...)} helpers.
     */
    public String callAgent(Map<String, Object> agent, String input) {
        if (agent == null) return "";
        return callAgentByNode(mapper.valueToTree(agent), input);
    }

    /** Tree-shaped overload used by {@link #runGraph} once we've wrapped the DTO. */
    public String callAgentByNode(JsonNode agent, String input) {
        if (agent == null) return "";
        String systemPrompt = agent.path("systemPrompt").asText("");
        String userPrompt = agent.path("userPrompt").asText("{{input}}");
        String responseFormat = agent.hasNonNull("responseFormat") ? agent.get("responseFormat").asText(null) : null;
        // Honor strict-JSON when the agent asks for it. Falls back to the
        // default generateJson (which a provider is free to implement loosely)
        // when strictOutput is false/missing. OpenAi's strict path uses
        // response_format: { type: "json_schema", strict: true }.
        boolean strict = agent.path("strictOutput").asBoolean(false);

        String resolvedUser = interpolate(userPrompt, input);
        String hint = (systemPrompt == null ? "" : systemPrompt) + "\n\n" + resolvedUser;
        String contextJson = "{\"input\":" + mapper.valueToTree(input == null ? "" : input) + "}";

        EngineSession session = newSession(input);
        if (responseFormat != null && !responseFormat.isBlank()) {
            return strict
                    ? llmClient.generateJsonStrict(session, hint, responseFormat, contextJson)
                    : llmClient.generateJson(session, hint, responseFormat, contextJson);
        }
        return llmClient.generateText(session, hint, contextJson);
    }

    /**
     * Full-graph run invoked by {@code POST /builder-studio/run}. Mirrors the
     * client-side runner's semantics so either side can drive execution.
     */
    public RunResponse runGraph(BuilderStudioController.RunRequest req) {
        RunResponse resp = new RunResponse();
        // Lift the Map-shaped workflow into a fasterxml tree — all the
        // walker logic below was written against JsonNode.
        JsonNode wf = req.getWorkflow() == null ? null : mapper.valueToTree(req.getWorkflow());
        if (wf == null || !wf.hasNonNull("nodes")) {
            resp.setError("workflow.nodes is required");
            return resp;
        }

        Map<String, JsonNode> nodesById = new HashMap<>();
        for (JsonNode n : wf.get("nodes")) nodesById.put(n.get("id").asText(), n);

        Map<String, List<String>> incoming = new HashMap<>();
        if (wf.has("edges")) {
            for (JsonNode e : wf.get("edges")) {
                incoming.computeIfAbsent(e.get("target").asText(), k -> new ArrayList<>())
                        .add(e.get("source").asText());
            }
        }

        JsonNode subs = wf.path("subBlockValues");
        Map<String, String> outputs = new HashMap<>();
        Set<String> started = new HashSet<>();
        Map<String, String> inputs = req.getInputs() == null ? Collections.emptyMap() : req.getInputs();

        // Seed user_input + starter nodes.
        for (JsonNode n : wf.get("nodes")) {
            String id = n.get("id").asText();
            String type = n.path("data").path("blockType").asText();
            if ("user_input".equals(type)) {
                String v = inputs.getOrDefault(id, "");
                outputs.put(id, v);
                started.add(id);
                addTrace(resp, id, type, n.path("data").path("title").asText(""), null, v, 0L);
            } else if ("starter".equals(type)) {
                outputs.put(id, "");
                started.add(id);
            }
        }

        // Ready-set BFS with parallel dispatch.
        while (true) {
            List<JsonNode> ready = new ArrayList<>();
            for (JsonNode n : wf.get("nodes")) {
                String id = n.get("id").asText();
                if (started.contains(id)) continue;
                List<String> ins = incoming.getOrDefault(id, Collections.emptyList());
                if (ins.stream().allMatch(started::contains)) ready.add(n);
            }
            if (ready.isEmpty()) break;

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (JsonNode n : ready) {
                String id = n.get("id").asText();
                started.add(id);
                String type = n.path("data").path("blockType").asText();
                String title = n.path("data").path("title").asText("");
                List<String> ins = incoming.getOrDefault(id, Collections.emptyList());
                String input = ins.isEmpty() ? "" : outputs.getOrDefault(ins.get(0), "");

                futures.add(CompletableFuture.runAsync(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        String out = runNode(type, subs.path(id), input);
                        synchronized (outputs) { outputs.put(id, out == null ? "" : out); }
                        addTrace(resp, id, type, title, input, outputs.get(id), System.currentTimeMillis() - t0);
                    } catch (Exception e) {
                        log.error("node {} ({}) failed", id, type, e);
                        synchronized (outputs) { outputs.put(id, ""); }
                        RunResponse.TraceEntry entry = addTrace(resp, id, type, title, input, null,
                                System.currentTimeMillis() - t0);
                        entry.setError(e.getMessage());
                    }
                }, executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                resp.setError(e.getMessage());
                return resp;
            }
        }

        // Final output: the `response` node, or the last produced value.
        String finalOutput = null;
        for (JsonNode n : wf.get("nodes")) {
            if ("response".equals(n.path("data").path("blockType").asText())) {
                finalOutput = outputs.get(n.get("id").asText());
                break;
            }
        }
        if (finalOutput == null && !resp.getTrace().isEmpty()) {
            finalOutput = resp.getTrace().get(resp.getTrace().size() - 1).getOutput();
        }
        resp.setOutput(finalOutput);
        return resp;
    }

    /* --- per-block execution ---------------------------------------------- */

    private String runNode(String type, JsonNode values, String input) {
        if (type == null) return input;
        switch (type) {
            case "starter":
            case "user_input":
                return input;
            case "response":
                return interpolate(values.path("data").asText(""), input);
            case "agent":
                return callAgentByNode(synthAgentNode(values), input);
            default:
                // Control-flow blocks (if_else, switch, loops) are handled on the
                // client. On the server we just pass through.
                return input;
        }
    }

    /** Build an agent-shaped JsonNode from the `agent` subBlockValues bucket. */
    private JsonNode synthAgentNode(JsonNode values) {
        return mapper.createObjectNode()
                .put("model", values.path("model").asText("gpt-4o-mini"))
                .put("systemPrompt", values.path("systemPrompt").asText(""))
                .put("userPrompt", values.path("userPrompt").asText("{{input}}"))
                .put("responseFormat", values.path("responseFormat").asText(null))
                .put("strictOutput", values.path("strictOutput").asBoolean(false));
    }

    private EngineSession newSession(String userText) {
        EngineContext ctx = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText == null ? "" : userText)
                .inputParams(new HashMap<>())
                .build();
        return new EngineSession(ctx, mapper);
    }

    private static String interpolate(String template, String input) {
        if (template == null) return "";
        return template.replace("{{input}}", input == null ? "" : input);
    }

    private static RunResponse.TraceEntry addTrace(RunResponse resp, String nodeId, String type,
                                                   String title, String input, String output, long ms) {
        RunResponse.TraceEntry e = new RunResponse.TraceEntry();
        e.setNodeId(nodeId);
        e.setBlockType(type);
        e.setTitle(title);
        e.setInput(truncate(input));
        e.setOutput(truncate(output));
        e.setMs(ms);
        synchronized (resp) { resp.getTrace().add(e); }
        return e;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }
}
