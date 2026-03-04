package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Framework fallback vector ranking (least priority).
 * Consumers can override by providing a higher-priority interceptor bean.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultSemanticCatalogVectorSearchInterceptor implements SemanticCatalogVectorSearchInterceptor {

    private final ObjectProvider<LlmClient> llmClientProvider;

    @Override
    public boolean supports(ConvEngineMcpConfig.Db.Knowledge.VectorSearch config) {
        return config != null && config.isEnabled();
    }

    @Override
    public List<Map<String, Object>> rank(
            String sourceType,
            String question,
            List<Map<String, Object>> rows,
            ConvEngineMcpConfig.Db.Knowledge cfg) {

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        ConvEngineMcpConfig.Db.Knowledge.VectorSearch vectorCfg = cfg == null || cfg.getVectorSearch() == null
                ? new ConvEngineMcpConfig.Db.Knowledge.VectorSearch()
                : cfg.getVectorSearch();
        if (!vectorCfg.isEnabled() || question == null || question.isBlank()) {
            return rows;
        }

        LlmClient llmClient = llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            return rows;
        }

        float[] queryEmbedding;
        try {
            queryEmbedding = llmClient.generateEmbedding(question);
        } catch (Exception ex) {
            return rows;
        }
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return rows;
        }

        String vectorColumn = vectorCfg.getVectorColumn() == null
                ? "vector"
                : vectorCfg.getVectorColumn().trim().toLowerCase(Locale.ROOT);

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            float[] vector = parseVector(row == null ? null : row.get(vectorColumn));
            if (vector == null || vector.length == 0 || vector.length != queryEmbedding.length) {
                continue;
            }
            double score = cosineSimilarity(queryEmbedding, vector);
            if (score <= 0.0d) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("_vector_score", score);
            scored.add(item);
        }

        if (scored.isEmpty()) {
            return rows;
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> asDouble(m.get("_vector_score"))).reversed());
        int limit = Math.max(1, vectorCfg.getMaxResults());
        return scored.stream().limit(limit).toList();
    }

    private float[] parseVector(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return null;
        }
        String normalized = text;
        if (normalized.startsWith("[")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("]")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] parts = normalized.split(",");
        if (parts.length == 0) {
            return null;
        }
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i] == null ? "" : parts[i].trim();
            if (token.isBlank()) {
                return null;
            }
            try {
                out[i] = Float.parseFloat(token);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
            normA += (double) a[i] * (double) a[i];
            normB += (double) b[i] * (double) b[i];
        }
        if (normA <= 0.0d || normB <= 0.0d) {
            return 0.0d;
        }
        double cosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return (cosine + 1.0d) / 2.0d;
    }

    private double asDouble(Object value) {
        if (value == null) {
            return 0.0d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }
}

