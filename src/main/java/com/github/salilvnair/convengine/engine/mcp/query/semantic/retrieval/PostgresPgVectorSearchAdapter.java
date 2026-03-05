package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class PostgresPgVectorSearchAdapter implements SemanticVectorSearchAdapter {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ConvEngineMcpConfig mcpConfig;

    @Override
    public String adapterName() {
        return "pgvector";
    }

    @Override
    public boolean supports(EngineSession session) {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? null : mcpConfig.getDb().getSemantic();
        return cfg != null && cfg.getVector() != null && cfg.getVector().isEnabled();
    }

    @Override
    public Map<String, Double> entityScores(EngineSession session, float[] queryVector) {
        return queryByType(queryVector, "ENTITY");
    }

    @Override
    public Map<String, Double> tableScores(EngineSession session, float[] queryVector) {
        return queryByType(queryVector, "TABLE");
    }

    private Map<String, Double> queryByType(float[] queryVector, String targetType) {
        if (queryVector == null || queryVector.length == 0) {
            return Map.of();
        }
        ConvEngineMcpConfig.Db.Semantic.Vector cfg = mcpConfig.getDb().getSemantic().getVector();
        String table = cfg.getTable();
        String namespaceCol = cfg.getNamespaceColumn();
        String typeCol = cfg.getTargetTypeColumn();
        String nameCol = cfg.getTargetNameColumn();
        String embCol = cfg.getEmbeddingColumn();
        String sql = """
                SELECT %s AS target_name,
                       1 - (%s <=> CAST(:queryVec AS vector)) AS score
                FROM %s
                WHERE UPPER(%s) = :targetType
                ORDER BY %s <=> CAST(:queryVec AS vector)
                LIMIT :maxRows
                """.formatted(nameCol, embCol, table, typeCol, embCol);
        try {
            String vecLiteral = toVectorLiteral(queryVector);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, Map.of(
                    "queryVec", vecLiteral,
                    "targetType", targetType.toUpperCase(Locale.ROOT),
                    "maxRows", Math.max(cfg.getMaxResults(), 1)
            ));
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object key = row.get("target_name");
                Object score = row.get("score");
                if (key == null || score == null) {
                    continue;
                }
                out.put(String.valueOf(key), Math.max(0.0d, Math.min(1.0d, toDouble(score))));
            }
            return out;
        } catch (Exception ex) {
            log.debug("pgvector lookup failed for targetType={}. Falling back to deterministic retrieval. cause={}", targetType, ex.getMessage());
            return Map.of();
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignore) {
            return 0.0d;
        }
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        out.append(']');
        return out.toString();
    }
}
