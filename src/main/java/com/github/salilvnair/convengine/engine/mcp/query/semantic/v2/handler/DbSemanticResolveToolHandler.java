package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticResolveService;
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
public class DbSemanticResolveToolHandler implements DbToolHandler {

    private final SemanticResolveService resolveService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String toolCode() {
        return "db.semantic.resolve";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        CanonicalIntent canonicalIntent = parseCanonicalIntent(safeArgs.get("canonicalIntent"));
        if (canonicalIntent == null) {
            canonicalIntent = parseCanonicalIntent(safeArgs.get("canonical_intent"));
        }

        String conversationId = asText(safeArgs.get("conversationId"));
        if ((conversationId == null || conversationId.isBlank()) && session != null && session.getConversationId() != null) {
            conversationId = session.getConversationId().toString();
        }

        Map<String, Object> context = asMap(safeArgs.get("context"));

        SemanticResolveRequest request = new SemanticResolveRequest(canonicalIntent, conversationId, context);
        return resolveService.resolve(request, session);
    }

    private CanonicalIntent parseCanonicalIntent(Object value) {
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
        } catch (Exception ex) {
            return null;
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }
}
