package com.github.salilvnair.convengine.engine.mcp.query.semantic.embedding;

import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildResponse;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildResponse;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.*;
import com.github.salilvnair.convengine.entity.CeUserQueryKnowledge;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticEmbeddingService {

    private static final String TARGET_ENTITY = "ENTITY";
    private static final String TARGET_TABLE = "TABLE";
    private static final String TARGET_USER_QUERY = "USER_QUERY";

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticModelRegistry semanticModelRegistry;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    public SemanticEmbeddingRebuildResponse rebuildFromSemanticModel(SemanticEmbeddingRebuildRequest request) {
        SemanticEmbeddingRebuildRequest safeRequest = request == null ? new SemanticEmbeddingRebuildRequest() : request;
        ConvEngineMcpConfig.Db.Semantic cfg = semanticConfig();
        if (cfg.getVector() == null || !cfg.getVector().isEnabled()) {
            return SemanticEmbeddingRebuildResponse.builder()
                    .success(false)
                    .embeddingEnabled(false)
                    .namespace(resolveNamespace(safeRequest))
                    .candidateCount(0)
                    .indexedCount(0)
                    .failedCount(0)
                    .message("Semantic vector retrieval is disabled. Set convengine.mcp.db.semantic.vector.enabled=true.")
                    .build();
        }

        List<EmbeddingDoc> docs = buildModelDocs(safeRequest);
        int indexedCount = 0;
        int failedCount = 0;
        String namespace = resolveNamespace(safeRequest);

        if (Boolean.TRUE.equals(safeRequest.getClearNamespace())) {
            clearNamespace(namespace, cfg);
        }

        for (EmbeddingDoc doc : docs) {
            try {
                float[] embedding = llmClient.generateEmbedding(null, doc.text());
                if (embedding == null || embedding.length == 0) {
                    failedCount++;
                    continue;
                }
                upsertEmbedding(cfg, namespace, doc.targetType(), doc.targetName(), embedding, doc.metadata());
                indexedCount++;
            } catch (Exception ex) {
                failedCount++;
                log.debug("semantic embedding rebuild skip target={} type={} cause={}", doc.targetName(), doc.targetType(), ex.getMessage());
            }
        }

        return SemanticEmbeddingRebuildResponse.builder()
                .success(failedCount == 0 || indexedCount > 0)
                .embeddingEnabled(true)
                .namespace(namespace)
                .candidateCount(docs.size())
                .indexedCount(indexedCount)
                .failedCount(failedCount)
                .message("Semantic embedding rebuild completed.")
                .build();
    }

    public boolean indexUserQueryKnowledge(CeUserQueryKnowledge knowledge) {
        if (knowledge == null || knowledge.getQueryText() == null || knowledge.getQueryText().isBlank()) {
            return false;
        }
        ConvEngineMcpConfig.Db.Semantic cfg = semanticConfig();
        if (cfg.getVector() == null || !cfg.getVector().isEnabled()) {
            return false;
        }
        if (!cfg.getVector().isFeedbackEnabled()) {
            return false;
        }
        String text = buildUserKnowledgeEmbeddingText(knowledge);
        try {
            float[] embedding = llmClient.generateEmbedding(null, text);
            if (embedding == null || embedding.length == 0) {
                return false;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("feedbackType", knowledge.getFeedbackType());
            metadata.put("toolCode", knowledge.getToolCode());
            metadata.put("intentCode", knowledge.getIntentCode());
            metadata.put("stateCode", knowledge.getStateCode());
            metadata.put("queryText", knowledge.getQueryText());
            metadata.put("preparedSql", knowledge.getPreparedSql());
            metadata.put("knowledgeId", knowledge.getId());

            String targetName = knowledge.getId() == null
                    ? UUID.randomUUID().toString()
                    : "user_qk_" + knowledge.getId();
            upsertEmbedding(cfg, "user-query-knowledge", TARGET_USER_QUERY, targetName, embedding, metadata);
            knowledge.setEmbedding(vectorLiteral(embedding));
            return true;
        } catch (Exception ex) {
            log.debug("user-query embedding index failed. cause={}", ex.getMessage());
            return false;
        }
    }

    public SemanticEmbeddingCatalogRebuildResponse rebuildEmbeddingCatalog(SemanticEmbeddingCatalogRebuildRequest request) {
        SemanticEmbeddingCatalogRebuildRequest safeRequest =
                request == null ? new SemanticEmbeddingCatalogRebuildRequest() : request;

        int limit = safeRequest.getLimit() == null ? 200 : Math.max(1, Math.min(2000, safeRequest.getLimit()));
        boolean onlyMissing = safeRequest.getOnlyMissing() == null || safeRequest.getOnlyMissing();
        String queryClassKey = trimToNull(safeRequest.getQueryClassKey());
        String entityKey = trimToNull(safeRequest.getEntityKey());
        String embeddingModel = trimToNull(safeRequest.getEmbeddingModel());
        String embeddingVersion = trimToNull(safeRequest.getEmbeddingVersion());

        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.source_text
                FROM ce_semantic_concept_embedding e
                WHERE e.enabled = true
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (queryClassKey != null) {
            sql.append("""
                    AND EXISTS (
                        SELECT 1
                        FROM ce_semantic_mapping m
                        WHERE m.enabled = true
                          AND UPPER(m.concept_key) = UPPER(e.concept_key)
                          AND (m.query_class_key IS NULL OR UPPER(m.query_class_key) = UPPER(:queryClassKey))
                    )
                    """);
            params.put("queryClassKey", queryClassKey);
        }
        if (entityKey != null) {
            sql.append("""
                    AND EXISTS (
                        SELECT 1
                        FROM ce_semantic_mapping m
                        WHERE m.enabled = true
                          AND UPPER(m.concept_key) = UPPER(e.concept_key)
                          AND UPPER(m.entity_key) = UPPER(:entityKey)
                          AND (:queryClassKey IS NULL OR m.query_class_key IS NULL OR UPPER(m.query_class_key) = UPPER(:queryClassKey))
                    )
                    """);
            params.put("entityKey", entityKey);
        }
        if (onlyMissing) {
            sql.append(" AND (e.embedding_text IS NULL OR CAST(e.embedding_text AS text) = 'null' OR CAST(e.embedding_text AS text) = '[]')");
        }
        sql.append(" ORDER BY COALESCE(e.priority, 999999), e.id");
        sql.append(" LIMIT :limit");
        params.put("limit", limit);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params);
        int indexed = 0;
        int failed = 0;
        int skipped = 0;

        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            String sourceText = row.get("source_text") == null ? null : String.valueOf(row.get("source_text"));
            if (id == null || sourceText == null || sourceText.isBlank()) {
                skipped++;
                continue;
            }
            try {
                float[] embedding = llmClient.generateEmbedding(null, sourceText);
                if (embedding == null || embedding.length == 0) {
                    failed++;
                    continue;
                }
                String embeddingJson = JsonUtil.toJson(toNumberList(embedding));
                Map<String, Object> updateParams = new LinkedHashMap<>();
                updateParams.put("id", id);
                updateParams.put("embeddingJson", embeddingJson);
                updateParams.put("embeddingModel", embeddingModel);
                updateParams.put("embeddingVersion", embeddingVersion);
                jdbcTemplate.update("""
                        UPDATE ce_semantic_concept_embedding
                        SET embedding_text = CAST(:embeddingJson AS jsonb),
                            embedding_model = COALESCE(:embeddingModel, embedding_model),
                            embedding_version = COALESCE(:embeddingVersion, embedding_version)
                        WHERE id = :id
                        """, updateParams);
                indexed++;
            } catch (Exception ex) {
                failed++;
                log.debug("semantic embedding catalog refresh skip id={} cause={}", id, ex.getMessage());
            }
        }

        return SemanticEmbeddingCatalogRebuildResponse.builder()
                .success(failed == 0 || indexed > 0)
                .candidateCount(rows.size())
                .indexedCount(indexed)
                .failedCount(failed)
                .skippedCount(skipped)
                .queryClassKey(queryClassKey)
                .entityKey(entityKey)
                .message("Semantic embedding catalog refresh completed.")
                .build();
    }

    private List<EmbeddingDoc> buildModelDocs(SemanticEmbeddingRebuildRequest request) {
        SemanticModel model = semanticModelRegistry.getModel();
        List<EmbeddingDoc> docs = new ArrayList<>();

        if (Boolean.TRUE.equals(request.getIncludeEntities())) {
            for (Map.Entry<String, SemanticEntity> e : model.entities().entrySet()) {
                String entityName = e.getKey();
                SemanticEntity entity = e.getValue();
                StringBuilder text = new StringBuilder();
                text.append("Entity: ").append(entityName).append('\n');
                if (entity != null) {
                    appendIfPresent(text, "Description", entity.description());
                    if (entity.synonyms() != null && !entity.synonyms().isEmpty()) {
                        text.append("Synonyms: ").append(String.join(", ", entity.synonyms())).append('\n');
                    }
                    if (entity.fields() != null && !entity.fields().isEmpty()) {
                        for (Map.Entry<String, SemanticField> f : entity.fields().entrySet()) {
                            text.append("Field: ").append(f.getKey());
                            if (f.getValue() != null && f.getValue().description() != null) {
                                text.append(" - ").append(f.getValue().description());
                            }
                            text.append('\n');
                        }
                    }
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", "semantic-model");
                metadata.put("modelVersion", model.version());
                metadata.put("database", model.database());
                metadata.put("entity", entityName);
                docs.add(new EmbeddingDoc(TARGET_ENTITY, entityName, text.toString(), metadata));
            }
        }

        if (Boolean.TRUE.equals(request.getIncludeTables())) {
            for (Map.Entry<String, SemanticTable> t : model.tables().entrySet()) {
                String tableName = t.getKey();
                SemanticTable table = t.getValue();
                StringBuilder text = new StringBuilder();
                text.append("Table: ").append(tableName).append('\n');
                if (table != null) {
                    appendIfPresent(text, "Description", table.description());
                    if (table.columns() != null && !table.columns().isEmpty()) {
                        for (Map.Entry<String, SemanticColumn> c : table.columns().entrySet()) {
                            text.append("Column: ").append(c.getKey());
                            if (c.getValue() != null && c.getValue().description() != null) {
                                text.append(" - ").append(c.getValue().description());
                            }
                            text.append('\n');
                        }
                    }
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", "semantic-model");
                metadata.put("modelVersion", model.version());
                metadata.put("database", model.database());
                metadata.put("table", tableName);
                docs.add(new EmbeddingDoc(TARGET_TABLE, tableName, text.toString(), metadata));
            }
        }
        return docs;
    }

    private void upsertEmbedding(ConvEngineMcpConfig.Db.Semantic cfg,
                                 String namespace,
                                 String targetType,
                                 String targetName,
                                 float[] embedding,
                                 Map<String, Object> metadata) {
        String table = cfg.getVector().getTable();
        String namespaceCol = cfg.getVector().getNamespaceColumn();
        String targetTypeCol = cfg.getVector().getTargetTypeColumn();
        String targetNameCol = cfg.getVector().getTargetNameColumn();
        String embeddingCol = cfg.getVector().getEmbeddingColumn();
        String metadataCol = cfg.getVector().getMetadataColumn();

        String dialect = cfg.getSqlDialect() == null ? "postgres" : cfg.getSqlDialect().toLowerCase(Locale.ROOT);
        String sql;
        if ("postgres".equals(dialect)) {
            sql = """
                    INSERT INTO %s (%s, %s, %s, %s, %s, created_at, updated_at)
                    VALUES (:namespace, :targetType, :targetName, CAST(:embedding AS vector), CAST(:metadataJson AS jsonb), now(), now())
                    ON CONFLICT (%s, %s, %s)
                    DO UPDATE SET %s = EXCLUDED.%s,
                                  %s = EXCLUDED.%s,
                                  updated_at = now()
                    """.formatted(
                    table, namespaceCol, targetTypeCol, targetNameCol, embeddingCol, metadataCol,
                    namespaceCol, targetTypeCol, targetNameCol, embeddingCol, embeddingCol, metadataCol, metadataCol
            );
        } else {
            sql = """
                    INSERT INTO %s (%s, %s, %s, %s, %s, created_at, updated_at)
                    VALUES (:namespace, :targetType, :targetName, :embedding, :metadataJson, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(table, namespaceCol, targetTypeCol, targetNameCol, embeddingCol, metadataCol);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", namespace);
        params.put("targetType", targetType);
        params.put("targetName", targetName);
        params.put("embedding", vectorLiteral(embedding));
        params.put("metadataJson", JsonUtil.toJson(metadata == null ? Map.of() : metadata));
        jdbcTemplate.update(sql, params);
    }

    private void clearNamespace(String namespace, ConvEngineMcpConfig.Db.Semantic cfg) {
        String sql = "DELETE FROM " + cfg.getVector().getTable()
                + " WHERE " + cfg.getVector().getNamespaceColumn() + " = :namespace";
        jdbcTemplate.update(sql, Map.of("namespace", namespace));
    }

    private String resolveNamespace(SemanticEmbeddingRebuildRequest request) {
        String namespace = request == null ? null : request.getNamespace();
        if (namespace != null && !namespace.isBlank()) {
            return namespace.trim();
        }
        String db = semanticModelRegistry.getModel().database();
        if (db != null && !db.isBlank()) {
            return "semantic-model:" + db.trim();
        }
        return "semantic-model:default";
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<Double> toNumberList(float[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(values.length);
        for (float v : values) {
            out.add((double) v);
        }
        return out;
    }

    private ConvEngineMcpConfig.Db.Semantic semanticConfig() {
        if (mcpConfig.getDb() == null) {
            return new ConvEngineMcpConfig.Db.Semantic();
        }
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb().getSemantic();
        return cfg == null ? new ConvEngineMcpConfig.Db.Semantic() : cfg;
    }

    private void appendIfPresent(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        text.append(label).append(": ").append(value).append('\n');
    }

    private String buildUserKnowledgeEmbeddingText(CeUserQueryKnowledge knowledge) {
        StringBuilder text = new StringBuilder();
        appendIfPresent(text, "Query", knowledge.getQueryText());
        appendIfPresent(text, "Description", knowledge.getDescription());
        appendIfPresent(text, "Tool", knowledge.getToolCode());
        appendIfPresent(text, "Intent", knowledge.getIntentCode());
        appendIfPresent(text, "State", knowledge.getStateCode());
        appendIfPresent(text, "Prepared SQL", knowledge.getPreparedSql());
        appendIfPresent(text, "Tags", knowledge.getTags());
        appendIfPresent(text, "ApiHints", knowledge.getApiHints());
        appendIfPresent(text, "FeedbackType", knowledge.getFeedbackType());
        return text.toString();
    }

    private String vectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
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

    private record EmbeddingDoc(
            String targetType,
            String targetName,
            String text,
            Map<String, Object> metadata
    ) {
    }
}
