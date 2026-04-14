package com.github.salilvnair.convengine.engine.mcp.query.semantic.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.service.SemanticLlmQueryService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.semantic", name = "enabled", havingValue = "true")
public class DbSemanticQueryToolHandler implements DbToolHandler {

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticLlmQueryService llmQueryService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        return cfg.getToolCode() == null || cfg.getToolCode().isBlank() ? "db.semantic.query" : cfg.getToolCode();
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        CanonicalIntent canonicalIntent = parseCanonicalIntent(args);
        if (canonicalIntent == null) {
            throw new IllegalArgumentException(
                    "db.semantic.query is configured as v2-llm only. canonicalIntent is required and legacy runtime/resolve paths are disabled.");
        }
        String question = extractQuestion(args, session);
        return llmQueryService.query(canonicalIntent, question, session);
    }

    private CanonicalIntent parseCanonicalIntent(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        Object value = args.containsKey("canonicalIntent")
                ? args.get("canonicalIntent")
                : args.get("canonical_intent");
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof CanonicalIntent intent) {
                return intent;
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
                return mapper.convertValue(normalized, CanonicalIntent.class);
            }
            String raw = String.valueOf(value).trim();
            if (raw.startsWith("{") && raw.endsWith("}")) {
                return mapper.readValue(raw, CanonicalIntent.class);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractQuestion(Map<String, Object> args, EngineSession session) {
        if (args != null) {
            Object query = args.get("question");
            if (query == null) {
                query = args.get("query");
            }
            if (query == null) {
                query = args.get("user_input");
            }
            if (query != null && !String.valueOf(query).isBlank()) {
                return String.valueOf(query);
            }
        }
        return session == null ? "" : session.getUserText();
    }
}
