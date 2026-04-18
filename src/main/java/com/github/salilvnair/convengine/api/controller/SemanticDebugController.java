package com.github.salilvnair.convengine.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticCompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticInterpretRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticInterpretResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticQueryResponseV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.service.SemanticDeterministicInterpretService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.service.SemanticInterpretService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.service.SemanticLlmQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

// Thin REST facade for the semantic query pipeline. The /conversation path still
// runs the full 27-step pipeline; this controller lets the debug page call each
// stage in isolation, or run them end-to-end without touching ConvEngine's
// orchestration. Two modes:
//
//   mode=llm   → SemanticInterpretService (LLM)     → SemanticLlmQueryService (LLM) → execute
//   mode=java  → SemanticDeterministicInterpretService → SemanticLlmQueryService (LLM) → execute
//
// The "java" mode only skips the LLM interpret step (regex/keyword match over
// the semantic entity catalog); SQL compilation is still LLM-driven because
// the backend has no deterministic SQL emitter today.
@Slf4j
@RestController
@RequestMapping("/api/v1/semantic")
@RequiredArgsConstructor
public class SemanticDebugController {

    private final SemanticInterpretService interpretService;
    private final SemanticDeterministicInterpretService deterministicInterpretService;
    private final SemanticLlmQueryService llmQueryService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @PostMapping("/interpret")
    public ResponseEntity<SemanticInterpretResponse> interpret(@RequestBody Map<String, Object> body) {
        String question = str(body, "question");
        String mode = str(body, "mode");
        Map<String, Object> context = castMap(body.get("context"));
        Map<String, Object> hints = castMap(body.get("hints"));

        SemanticInterpretRequest req = new SemanticInterpretRequest(question, null, context, hints);
        SemanticInterpretResponse resp = "java".equalsIgnoreCase(mode)
                ? deterministicInterpretService.interpret(req)
                : interpretService.interpret(req, null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/compile")
    public ResponseEntity<SemanticQueryResponseV2> compile(@RequestBody Map<String, Object> body) {
        String question = str(body, "question");
        CanonicalIntent intent = objectMapper.convertValue(body.get("canonicalIntent"), CanonicalIntent.class);
        SemanticQueryResponseV2 resp = llmQueryService.query(intent, question, null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, Object> body) {
        String sql = str(body, "sql");
        Map<String, Object> params = castMap(body.get("params"));
        MapSqlParameterSource src = new MapSqlParameterSource();
        if (params != null) params.forEach(src::addValue);

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, src);
            out.put("ok", true);
            out.put("rowCount", rows.size());
            out.put("rows", rows);
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // End-to-end: interpret (LLM or Java) → compile (LLM) → optional execute.
    // Each stage's output is surfaced so the UI can show the full chain even
    // when a later stage throws.
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        String question = str(body, "question");
        String mode = str(body, "mode");
        boolean execute = Boolean.TRUE.equals(body.get("execute"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode == null ? "llm" : mode);
        out.put("question", question);

        SemanticInterpretResponse interpret;
        try {
            SemanticInterpretRequest req = new SemanticInterpretRequest(question, null, null, null);
            interpret = "java".equalsIgnoreCase(mode)
                    ? deterministicInterpretService.interpret(req)
                    : interpretService.interpret(req, null);
            out.put("interpret", interpret);
        } catch (Exception ex) {
            out.put("interpretError", ex.getMessage());
            return ResponseEntity.ok(out);
        }

        SemanticQueryResponseV2 compile;
        try {
            compile = llmQueryService.query(interpret.canonicalIntent(), question, null);
            out.put("compile", compile);
        } catch (Exception ex) {
            out.put("compileError", ex.getMessage());
            return ResponseEntity.ok(out);
        }

        if (execute && compile.compiledSql() != null) {
            SemanticCompiledSql sql = compile.compiledSql();
            MapSqlParameterSource src = new MapSqlParameterSource();
            if (sql.params() != null) sql.params().forEach(src::addValue);
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.sql(), src);
                Map<String, Object> exec = new LinkedHashMap<>();
                exec.put("ok", true);
                exec.put("rowCount", rows.size());
                exec.put("rows", rows);
                out.put("execute", exec);
            } catch (Exception ex) {
                Map<String, Object> exec = new LinkedHashMap<>();
                exec.put("ok", false);
                exec.put("error", ex.getMessage());
                out.put("execute", exec);
            }
        }
        return ResponseEntity.ok(out);
    }

    // SSE variant of /run. Emits named events ("stage") as the pipeline
    // progresses so the debug UI can animate the active step live instead of
    // staring at a spinner. Events:
    //   stage  → { stage: "INTERPRET"|"COMPILE"|"EXECUTE", phase: "start"|"done"|"error",
    //              detail: <stage-specific payload or error message>, ms: <elapsed> }
    //   done   → full aggregated payload (same shape as POST /run)
    //   error  → terminal failure
    private static final java.util.concurrent.ExecutorService SSE_POOL =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "semantic-debug-sse");
                t.setDaemon(true);
                return t;
            });

    @GetMapping(path = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(
            @RequestParam("question") String question,
            @RequestParam(value = "mode", defaultValue = "llm") String mode,
            @RequestParam(value = "execute", defaultValue = "false") boolean execute
    ) {
        SseEmitter emitter = new SseEmitter(5L * 60_000L); // 5 min
        SSE_POOL.submit(() -> runAndStream(emitter, question, mode, execute));
        return emitter;
    }

    private void runAndStream(SseEmitter emitter, String question, String mode, boolean execute) {
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("mode", mode == null ? "llm" : mode);
        aggregate.put("question", question);

        try {
            safeEmit(emitter, "stage", stage("PIPELINE", "start",
                    Map.of("mode", mode, "execute", execute, "service",
                            "java".equalsIgnoreCase(mode)
                                    ? "SemanticDeterministicInterpretService"
                                    : "SemanticInterpretService"),
                    0));

            // INTERPRET
            long t0 = System.currentTimeMillis();
            safeEmit(emitter, "stage", stage("INTERPRET", "start",
                    Map.of("service",
                            "java".equalsIgnoreCase(mode)
                                    ? "SemanticDeterministicInterpretService.interpret"
                                    : "SemanticInterpretService.interpret"),
                    0));

            SemanticInterpretResponse interpret;
            try {
                SemanticInterpretRequest req = new SemanticInterpretRequest(question, null, null, null);
                interpret = "java".equalsIgnoreCase(mode)
                        ? deterministicInterpretService.interpret(req)
                        : interpretService.interpret(req, null);
                aggregate.put("interpret", interpret);
                safeEmit(emitter, "stage", stage("INTERPRET", "done",
                        Map.of("entity", interpret.canonicalIntent() == null ? null
                                : interpret.canonicalIntent().entity(),
                                "queryClass", interpret.canonicalIntent() == null ? null
                                        : interpret.canonicalIntent().queryClass()),
                        System.currentTimeMillis() - t0));
            } catch (Exception ex) {
                aggregate.put("interpretError", ex.getMessage());
                safeEmit(emitter, "stage", stage("INTERPRET", "error",
                        Map.of("message", String.valueOf(ex.getMessage())),
                        System.currentTimeMillis() - t0));
                safeEmit(emitter, "done", aggregate);
                emitter.complete();
                return;
            }

            // COMPILE
            long t1 = System.currentTimeMillis();
            safeEmit(emitter, "stage", stage("COMPILE", "start",
                    Map.of("service", "SemanticLlmQueryService.query"),
                    0));

            SemanticQueryResponseV2 compile;
            try {
                compile = llmQueryService.query(interpret.canonicalIntent(), question, null);
                aggregate.put("compile", compile);
                SemanticCompiledSql cs = compile.compiledSql();
                safeEmit(emitter, "stage", stage("COMPILE", "done",
                        Map.of("hasSql", cs != null && cs.sql() != null,
                                "paramCount", cs == null || cs.params() == null ? 0 : cs.params().size()),
                        System.currentTimeMillis() - t1));
            } catch (Exception ex) {
                aggregate.put("compileError", ex.getMessage());
                safeEmit(emitter, "stage", stage("COMPILE", "error",
                        Map.of("message", String.valueOf(ex.getMessage())),
                        System.currentTimeMillis() - t1));
                safeEmit(emitter, "done", aggregate);
                emitter.complete();
                return;
            }

            // EXECUTE (optional)
            if (execute && compile.compiledSql() != null) {
                long t2 = System.currentTimeMillis();
                safeEmit(emitter, "stage", stage("EXECUTE", "start",
                        Map.of("service", "NamedParameterJdbcTemplate.queryForList"),
                        0));
                SemanticCompiledSql sql = compile.compiledSql();
                MapSqlParameterSource src = new MapSqlParameterSource();
                if (sql.params() != null) sql.params().forEach(src::addValue);
                try {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.sql(), src);
                    Map<String, Object> exec = new LinkedHashMap<>();
                    exec.put("ok", true);
                    exec.put("rowCount", rows.size());
                    exec.put("rows", rows);
                    aggregate.put("execute", exec);
                    safeEmit(emitter, "stage", stage("EXECUTE", "done",
                            Map.of("rowCount", rows.size()),
                            System.currentTimeMillis() - t2));
                } catch (Exception ex) {
                    Map<String, Object> exec = new LinkedHashMap<>();
                    exec.put("ok", false);
                    exec.put("error", ex.getMessage());
                    aggregate.put("execute", exec);
                    safeEmit(emitter, "stage", stage("EXECUTE", "error",
                            Map.of("message", String.valueOf(ex.getMessage())),
                            System.currentTimeMillis() - t2));
                }
            }

            safeEmit(emitter, "stage", stage("PIPELINE", "done", Map.of(), 0));
            safeEmit(emitter, "done", aggregate);
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Semantic debug stream failed", ex);
            safeEmit(emitter, "error", Map.of("message", String.valueOf(ex.getMessage())));
            emitter.completeWithError(ex);
        }
    }

    private static Map<String, Object> stage(String name, String phase, Map<String, Object> detail, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", name);
        m.put("phase", phase);
        m.put("detail", detail);
        m.put("ms", ms);
        return m;
    }

    private static void safeEmit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            // client hung up — nothing to do
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
