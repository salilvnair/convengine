package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.handler;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryRequestV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSemanticPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticLlmQueryService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticQueryV2Service;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.semantic", name = "enabled", havingValue = "true")
public class DbSemanticQueryToolHandler implements DbToolHandler {

    private static final Pattern NON_READ_ONLY_SQL_PATTERN = Pattern.compile(
            "(?is)\\b(" +
                    "delete\\s+from" +
                    "|update\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s+set" +
                    "|drop\\s+table" +
                    "|truncate\\s+table" +
                    "|alter\\s+table" +
                    "|insert\\s+into" +
                    "|create\\s+table" +
                    "|grant\\s+[^\\s]+" +
                    "|revoke\\s+[^\\s]+" +
                    ")\\b"
    );

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticQueryRuntimeService runtimeService;
    private final SemanticQueryV2Service queryV2Service;
    private final SemanticLlmQueryService llmQueryService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        return cfg.getToolCode() == null || cfg.getToolCode().isBlank() ? "db.semantic.query" : cfg.getToolCode();
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        boolean deterministicMode = isDeterministicMode();
        CanonicalIntent canonicalIntent = parseCanonicalIntent(args);
        if (canonicalIntent != null) {
            String question = extractQuestion(args, session);
            return llmQueryService.query(canonicalIntent, question, session);
        }
        if (deterministicMode && hasResolvedPlan(args)) {
            SemanticQueryRequestV2 requestV2 = parseV2Request(args, session);
            return queryV2Service.query(requestV2, session);
        }
        String question = extractQuestion(args, session);
        if (isNonReadOnlyQuestion(question)) {
            return blockedResponse(question);
        }
        return runtimeService.plan(question, session);
    }

    private boolean hasResolvedPlan(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        return args.containsKey("resolvedPlan") || args.containsKey("resolved_plan");
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

    private boolean isDeterministicMode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        String mode = cfg == null ? null : cfg.getQueryMode();
        return mode != null && "deterministic".equalsIgnoreCase(mode.trim());
    }

    private SemanticQueryRequestV2 parseV2Request(Map<String, Object> args, EngineSession session) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Object resolvedPlanObject = safeArgs.containsKey("resolvedPlan")
                ? safeArgs.get("resolvedPlan")
                : safeArgs.get("resolved_plan");
        ResolvedSemanticPlan resolvedPlan = parseResolvedPlan(resolvedPlanObject);
        boolean strictMode = parseStrictMode(safeArgs);
        boolean dryRun = parseBoolean(safeArgs.get("dryRun"), false);
        if (safeArgs.containsKey("dry_run")) {
            dryRun = parseBoolean(safeArgs.get("dry_run"), dryRun);
        }
        String conversationId = safeArgs.get("conversationId") == null
                ? null
                : String.valueOf(safeArgs.get("conversationId"));
        if ((conversationId == null || conversationId.isBlank()) && session != null && session.getConversationId() != null) {
            conversationId = session.getConversationId().toString();
        }

        return new SemanticQueryRequestV2(resolvedPlan, strictMode, dryRun, conversationId);
    }

    private ResolvedSemanticPlan parseResolvedPlan(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof ResolvedSemanticPlan plan) {
                return plan;
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
                return mapper.convertValue(normalized, ResolvedSemanticPlan.class);
            }
            String raw = String.valueOf(value).trim();
            if (raw.startsWith("{") && raw.endsWith("}")) {
                return mapper.readValue(raw, ResolvedSemanticPlan.class);
            }
            return null;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid resolvedPlan payload for db.semantic.query v2: " + ex.getMessage(), ex);
        }
    }

    private boolean parseStrictMode(Map<String, Object> args) {
        boolean defaultValue = mcpConfig.getDb() != null
                && mcpConfig.getDb().getSemantic() != null
                && mcpConfig.getDb().getSemantic().isStrictMode();
        if (args == null || args.isEmpty()) {
            return defaultValue;
        }
        if (args.containsKey("strictMode")) {
            return parseBoolean(args.get("strictMode"), defaultValue);
        }
        if (args.containsKey("strict_mode")) {
            return parseBoolean(args.get("strict_mode"), defaultValue);
        }
        return defaultValue;
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "y".equalsIgnoreCase(text);
    }

    private boolean isNonReadOnlyQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return NON_READ_ONLY_SQL_PATTERN.matcher(question).find();
    }

    private Map<String, Object> blockedResponse(String question) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "semantic");
        out.put("question", question == null ? "" : question);
        out.put("blocked", true);
        out.put("reason", "READ_ONLY_GUARDRAIL");
        out.put("summary", "Blocked by semantic-query read-only guardrail. DML/DDL operations are not allowed.");
        out.put("next", "failed");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("toolCode", toolCode());
        meta.put("stageCode", "runtime-entry");
        meta.put("lifecycle", "BLOCKED");
        out.put("_meta", meta);
        return out;
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
