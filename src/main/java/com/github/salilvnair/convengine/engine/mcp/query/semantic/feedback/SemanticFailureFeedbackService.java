package com.github.salilvnair.convengine.engine.mcp.query.semantic.feedback;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineSqlTableResolver;
import com.github.salilvnair.convengine.entity.CeSemanticQueryFailure;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.repo.SemanticQueryFailureRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticFailureFeedbackService {

    private static final String AUDIT_STAGE = "SEMANTIC_FAILURE_RECORDED";
    private static final String SUCCESS_AUDIT_STAGE = "SEMANTIC_FAILURE_CORRECTED";

    private final SemanticQueryFailureRepository failureRepository;
    private final AuditService auditService;
    private final ObjectProvider<LlmClient> llmClientProvider;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    @Autowired(required = false)
    private ConvEngineSqlTableResolver tableResolver;

    public void recordFailure(SemanticFailureRecord record) {
        if (record == null) {
            return;
        }
        String question = normalize(record.question());
        if (question == null) {
            // Keep persisted rows meaningful and queryable.
            return;
        }
        try {
            Map<String, Object> metadata = safeMap(record.metadata());
            String standalone = normalize((String) metadata.getOrDefault("standaloneQuery", metadata.get("standalone_query")));
            if (standalone == null) {
                standalone = question;
            }
            metadata.put("standalone_query", standalone);
            enrichEmbedding(metadata, standalone);

            CeSemanticQueryFailure entity = CeSemanticQueryFailure.builder()
                    .conversationId(record.conversationId())
                    .question(question)
                    .generatedSql(normalize(record.generatedSql()))
                    .correctSql(normalize(record.correctSql()))
                    .rootCause(normalize(record.rootCause()))
                    .reason(normalize(record.reason()))
                    .stage(normalize(record.stage()))
                    .metadataJson(JsonUtil.toJson(metadata))
                    .build();

            CeSemanticQueryFailure saved = failureRepository.save(entity);
            persistEmbeddingQuestion(saved.getId(), embeddingFromMetadata(metadata));
            auditService.audit(AUDIT_STAGE, saved.getConversationId(), auditPayload(saved));
        } catch (Exception ex) {
            // Failure capture must never break user request flow.
            log.warn("Failed to persist semantic query failure feedback: {}", ex.getMessage());
        }
    }

    public void recordCorrection(UUID conversationId, String question, String correctedSql, Map<String, Object> metadata) {
        String q = normalize(question);
        String sql = normalize(correctedSql);
        if (conversationId == null || q == null || sql == null) {
            return;
        }
        try {
            Optional<CeSemanticQueryFailure> existingOpt =
                    failureRepository.findFirstByConversationIdAndQuestionAndCorrectSqlIsNullOrderByCreatedAtDesc(conversationId, q);
            if (existingOpt.isEmpty()) {
                return;
            }
            CeSemanticQueryFailure entity = existingOpt.get();
            entity.setCorrectSql(sql);
            Map<String, Object> mergedMeta = mergeMetadata(entity.getMetadataJson(), metadata);
            String standalone = normalize((String) mergedMeta.getOrDefault("standaloneQuery", mergedMeta.get("standalone_query")));
            if (standalone == null) {
                standalone = q;
            }
            mergedMeta.put("standalone_query", standalone);
            enrichEmbedding(mergedMeta, standalone);
            entity.setMetadataJson(JsonUtil.toJson(mergedMeta));
            CeSemanticQueryFailure saved = failureRepository.save(entity);
            persistEmbeddingQuestion(saved.getId(), embeddingFromMetadata(mergedMeta));
            auditService.audit(SUCCESS_AUDIT_STAGE, saved.getConversationId(), auditPayload(saved));
        } catch (Exception ex) {
            log.warn("Failed to persist semantic query correction feedback: {}", ex.getMessage());
        }
    }

    private Map<String, Object> auditPayload(CeSemanticQueryFailure saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("failure_id", saved.getId());
        payload.put("conversation_id", stringify(saved.getConversationId()));
        payload.put("stage", saved.getStage());
        payload.put("root_cause", saved.getRootCause());
        payload.put("has_generated_sql", saved.getGeneratedSql() != null && !saved.getGeneratedSql().isBlank());
        payload.put("has_correct_sql", saved.getCorrectSql() != null && !saved.getCorrectSql().isBlank());
        return payload;
    }

    private Map<String, Object> safeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(map);
    }

    private Map<String, Object> mergeMetadata(String existingJson, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (existingJson != null && !existingJson.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = JsonUtil.fromJson(existingJson, Map.class);
                if (existing != null) {
                    out.putAll(existing);
                }
            }
        } catch (Exception ignored) {
            // keep empty
        }
        if (patch != null && !patch.isEmpty()) {
            out.putAll(patch);
        }
        return out;
    }

    private void enrichEmbedding(Map<String, Object> metadata, String text) {
        if (metadata == null || text == null || text.isBlank()) {
            return;
        }
        if (metadata.containsKey("query_embedding")) {
            return;
        }
        LlmClient llmClient = llmClientProvider == null ? null : llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            return;
        }
        try {
            float[] vec = llmClient.generateEmbedding(null, text);
            if (vec == null || vec.length == 0) {
                return;
            }
            List<Float> out = new ArrayList<>(vec.length);
            for (float v : vec) {
                out.add(v);
            }
            metadata.put("query_embedding", out);
        } catch (Exception ignored) {
            // never break runtime
        }
    }

    private List<Float> embeddingFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object raw = metadata.get("query_embedding");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Float> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number n) {
                out.add(n.floatValue());
            } else if (item != null) {
                try {
                    out.add(Float.parseFloat(String.valueOf(item)));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    private void persistEmbeddingQuestion(Long id, List<Float> embedding) {
        if (id == null || embedding == null || embedding.isEmpty()) {
            return;
        }
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update(resolveSql("""
                    UPDATE ce_semantic_query_failures
                    SET question_embedding = CAST(:embedding AS vector)
                    WHERE id = :id
                    """), Map.of(
                    "id", id,
                    "embedding", vectorLiteral(embedding)
            ));
        } catch (Exception ignored) {
            // never break runtime
        }
    }

    private String vectorLiteral(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector.get(i));
        }
        out.append(']');
        return out.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stringify(UUID conversationId) {
        return conversationId == null ? null : conversationId.toString();
    }

    private String resolveSql(String sql) {
        return tableResolver == null ? sql : tableResolver.resolveSql(sql);
    }
}
