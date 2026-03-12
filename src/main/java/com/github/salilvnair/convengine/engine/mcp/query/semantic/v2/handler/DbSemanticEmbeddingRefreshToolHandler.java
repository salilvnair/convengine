package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.handler;

import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildResponse;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.embedding.SemanticEmbeddingService;
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
public class DbSemanticEmbeddingRefreshToolHandler implements DbToolHandler {

    private final SemanticEmbeddingService semanticEmbeddingService;

    @Override
    public String toolCode() {
        return "db.semantic.embed.refresh";
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        Map<String, Object> safe = args == null ? Map.of() : args;
        SemanticEmbeddingCatalogRebuildRequest request = new SemanticEmbeddingCatalogRebuildRequest();
        request.setQueryClassKey(asText(safe.get("queryClassKey")));
        request.setEntityKey(asText(safe.get("entityKey")));
        request.setOnlyMissing(asBoolean(safe.get("onlyMissing"), true));
        request.setLimit(asInt(safe.get("limit"), 200));
        request.setEmbeddingModel(asText(safe.get("embeddingModel")));
        request.setEmbeddingVersion(asText(safe.get("embeddingVersion")));

        SemanticEmbeddingCatalogRebuildResponse response = semanticEmbeddingService.rebuildEmbeddingCatalog(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolCode());
        out.put("ok", response.isSuccess());
        out.put("candidateCount", response.getCandidateCount());
        out.put("indexedCount", response.getIndexedCount());
        out.put("failedCount", response.getFailedCount());
        out.put("skippedCount", response.getSkippedCount());
        out.put("queryClassKey", response.getQueryClassKey());
        out.put("entityKey", response.getEntityKey());
        out.put("message", response.getMessage());
        return out;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Boolean asBoolean(Object value, boolean fallback) {
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
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text);
    }

    private Integer asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}

