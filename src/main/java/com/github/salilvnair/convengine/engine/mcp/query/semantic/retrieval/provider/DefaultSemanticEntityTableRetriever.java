package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order()
@RequiredArgsConstructor
public class DefaultSemanticEntityTableRetriever implements SemanticEntityTableRetriever {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern ID_LIKE_TOKEN = Pattern.compile("\\b[A-Z0-9_-]*\\d+[A-Z0-9_-]*\\b", Pattern.CASE_INSENSITIVE);
    private static final double FIELD_OWNERSHIP_WEIGHT = 0.45d;
    private static final double FEEDBACK_VECTOR_ENTITY_WEIGHT = 0.40d;
    private static final double FEEDBACK_VECTOR_TABLE_WEIGHT = 0.30d;
    private static final String FEEDBACK_TARGET_TYPE = "USER_QUERY";

    private final SemanticModelRegistry modelRegistry;
    private final ConvEngineMcpConfig mcpConfig;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final ObjectProvider<List<SemanticVectorSearchAdapter>> vectorAdaptersProvider;
    private final ObjectProvider<List<SemanticRetrievalInterceptor>> interceptorsProvider;

    @Override
    public RetrievalResult retrieve(String question, EngineSession session) {
        List<SemanticRetrievalInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        String safeQuestion = question == null ? "" : question;
        for (SemanticRetrievalInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeRetrieve(safeQuestion, session);
            }
        }
        try {
            RetrievalResult result = doRetrieve(safeQuestion, session);
            RetrievalResult current = result;
            for (SemanticRetrievalInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    current = interceptor.afterRetrieve(current, session);
                }
            }
            return current;
        } catch (Exception ex) {
            for (SemanticRetrievalInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(safeQuestion, session, ex);
                }
            }
            throw ex;
        }
    }

    private RetrievalResult doRetrieve(String question, EngineSession session) {
        SemanticModel model = modelRegistry.getModel();
        if (model.entities().isEmpty()) {
            return new RetrievalResult(question, List.of(), List.of(), "LOW");
        }

        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        ConvEngineMcpConfig.Db.Semantic.Retrieval rc = cfg.getRetrieval() == null ? new ConvEngineMcpConfig.Db.Semantic.Retrieval() : cfg.getRetrieval();

        Set<String> tokenSet = new LinkedHashSet<>(tokenize(question));
        boolean hasIdLikeToken = hasIdLikeToken(question);

        float[] qVec = null;
        if (cfg.getVector() != null && cfg.getVector().isEnabled()) {
            try {
                qVec = llmClient.generateEmbedding(session, question);
            } catch (Exception ex) {
                log.debug("Embedding generation failed; continuing deterministic retrieval. cause={}", ex.getMessage());
            }
        }

        Map<String, Double> vectorEntityScores = Map.of();
        Map<String, Double> vectorTableScores = Map.of();
        Map<String, Double> feedbackEntityBoost = Map.of();
        Map<String, Double> feedbackTableBoost = Map.of();
        if (qVec != null && qVec.length > 0) {
            SemanticVectorSearchAdapter adapter = resolveVectorAdapter(session);
            if (adapter != null) {
                vectorEntityScores = adapter.entityScores(session, qVec);
                vectorTableScores = adapter.tableScores(session, qVec);
            }
            FeedbackBoostResult feedbackBoost = loadFeedbackBoost(qVec, cfg);
            feedbackEntityBoost = feedbackBoost.entityBoostByName();
            feedbackTableBoost = feedbackBoost.tableBoostByName();
        }

        List<CandidateEntity> entities = new ArrayList<>();
        List<CandidateEntity> scoredEntities = new ArrayList<>();
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            String entityName = entry.getKey();
            SemanticEntity entity = entry.getValue();

            double synonym = overlapScore(tokenSet, tokenize(String.join(" ", entity.synonyms())));
            double fields = fieldScore(tokenSet, entity.fields());
            double idPattern = idPatternScore(entity, hasIdLikeToken);
            double lexical = overlapScore(tokenSet, tokenize(entity.description()));
            double fieldOwnership = strongFieldOwnershipScore(tokenSet, entity.fields());

            double deterministic = rc.getSynonymWeight() * synonym
                    + rc.getFieldWeight() * fields
                    + rc.getIdPatternWeight() * idPattern
                    + rc.getLexicalWeight() * lexical
                    + FIELD_OWNERSHIP_WEIGHT * fieldOwnership;
            double vector = vectorEntityScores.getOrDefault(entityName, 0.0d);
            double feedbackBoost = feedbackEntityBoost.getOrDefault(entityName, 0.0d);
            double total = rc.getDeterministicBlendWeight() * clamp01(deterministic)
                    + rc.getVectorBlendWeight() * clamp01(vector)
                    + FEEDBACK_VECTOR_ENTITY_WEIGHT * clamp01(feedbackBoost);
            Map<String, Double> signalScores = new LinkedHashMap<>();
            signalScores.put("synonym", round(synonym));
            signalScores.put("field", round(fields));
            signalScores.put("idPattern", round(idPattern));
            signalScores.put("lexical", round(lexical));
            signalScores.put("fieldOwnership", round(fieldOwnership));
            signalScores.put("feedbackBoost", round(feedbackBoost));

            CandidateEntity candidate = new CandidateEntity(entityName, round(total), round(deterministic), round(vector),
                    reasons(synonym, fields, idPattern, lexical, fieldOwnership, vector, feedbackBoost), signalScores);
            scoredEntities.add(candidate);

            if (total >= rc.getMinEntityScore()) {
                entities.add(candidate);
            }
        }

        entities.sort(Comparator.comparingDouble(CandidateEntity::score).reversed());
        scoredEntities.sort(Comparator.comparingDouble(CandidateEntity::score).reversed());
        // Fallback: avoid fully-empty retrieval when deterministic/vector scoring produced
        // weak but non-zero candidates.
        if (entities.isEmpty() && !scoredEntities.isEmpty() && scoredEntities.getFirst().score() > 0.0d) {
            entities.add(scoredEntities.getFirst());
        }
        if (entities.size() > rc.getMaxEntities()) {
            entities = new ArrayList<>(entities.subList(0, rc.getMaxEntities()));
        }

        Set<String> selectedEntityNames = new HashSet<>();
        for (CandidateEntity e : entities) {
            selectedEntityNames.add(e.name());
        }

        List<CandidateTable> tables = new ArrayList<>();
        List<CandidateTable> scoredTables = new ArrayList<>();
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            String entityName = entry.getKey();
            if (!selectedEntityNames.contains(entityName)) {
                continue;
            }
            SemanticEntity entity = entry.getValue();
            Set<String> entityTables = new LinkedHashSet<>();
            if (entity.tables() != null && entity.tables().primary() != null) {
                entityTables.add(entity.tables().primary());
                entityTables.addAll(entity.tables().related());
            }
            for (String tableName : entityTables) {
                double deterministic = tableDeterministicScore(tokenSet, tableName, entity);
                double vector = vectorTableScores.getOrDefault(tableName, 0.0d);
                double feedbackBoost = feedbackTableBoost.getOrDefault(tableName, 0.0d);
                double total = rc.getDeterministicBlendWeight() * clamp01(deterministic)
                        + rc.getVectorBlendWeight() * clamp01(vector)
                        + FEEDBACK_VECTOR_TABLE_WEIGHT * clamp01(feedbackBoost);
                CandidateTable candidate = new CandidateTable(tableName, entityName, round(total), round(deterministic), round(vector),
                        reasons(0.0d, 0.0d, 0.0d, deterministic, 0.0d, vector, feedbackBoost));
                scoredTables.add(candidate);
                if (total < rc.getMinTableScore()) {
                    continue;
                }
                tables.add(candidate);
            }
        }

        tables.sort(Comparator.comparingDouble(CandidateTable::score).reversed());
        scoredTables.sort(Comparator.comparingDouble(CandidateTable::score).reversed());
        if (tables.isEmpty() && !scoredTables.isEmpty() && scoredTables.getFirst().score() > 0.0d) {
            tables.add(scoredTables.getFirst());
        }
        if (tables.size() > rc.getMaxTables()) {
            tables = new ArrayList<>(tables.subList(0, rc.getMaxTables()));
        }

        String confidence = entities.isEmpty() ? "LOW" : (entities.get(0).score() >= 0.75d ? "HIGH" : "MEDIUM");
        return new RetrievalResult(question, List.copyOf(entities), List.copyOf(tables), confidence);
    }

    private SemanticVectorSearchAdapter resolveVectorAdapter(EngineSession session) {
        List<SemanticVectorSearchAdapter> adapters = vectorAdaptersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(adapters);
        for (SemanticVectorSearchAdapter adapter : adapters) {
            if (adapter != null && adapter.supports(session)) {
                return adapter;
            }
        }
        return null;
    }

    private double fieldScore(Set<String> queryTokens, Map<String, SemanticField> fields) {
        if (fields == null || fields.isEmpty()) {
            return 0.0d;
        }
        Set<String> flat = new LinkedHashSet<>();
        for (Map.Entry<String, SemanticField> e : fields.entrySet()) {
            flat.addAll(tokenize(e.getKey()));
            SemanticField field = e.getValue();
            if (field != null) {
                flat.addAll(tokenize(field.description()));
                flat.addAll(tokenize(field.column()));
            }
        }
        return overlapScore(queryTokens, flat);
    }

    private double tableDeterministicScore(Set<String> queryTokens, String tableName, SemanticEntity entity) {
        Set<String> doc = new LinkedHashSet<>(tokenize(tableName));
        if (entity.fields() != null) {
            entity.fields().forEach((name, field) -> {
                if (field != null && field.column() != null && field.column().toLowerCase(Locale.ROOT).startsWith(tableName.toLowerCase(Locale.ROOT) + ".")) {
                    doc.addAll(tokenize(name));
                    doc.addAll(tokenize(field.description()));
                }
            });
        }
        return overlapScore(queryTokens, doc);
    }

    private double idPatternScore(SemanticEntity entity, boolean hasIdLikeToken) {
        if (!hasIdLikeToken || entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return 0.0d;
        }
        boolean hasKeyIdField = false;
        boolean hasAnyIdField = false;
        for (Map.Entry<String, SemanticField> entry : entity.fields().entrySet()) {
            String fieldName = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            SemanticField field = entry.getValue();
            String column = field == null || field.column() == null ? "" : field.column().toLowerCase(Locale.ROOT);
            boolean looksLikeId = fieldName.contains("id") || column.endsWith("_id") || column.contains(".id");
            if (!looksLikeId) {
                continue;
            }
            hasAnyIdField = true;
            if (field != null && Boolean.TRUE.equals(field.key())) {
                hasKeyIdField = true;
            }
        }
        if (hasKeyIdField) {
            return 1.0d;
        }
        if (hasAnyIdField) {
            return 0.7d;
        }
        return 0.0d;
    }

    private boolean hasIdLikeToken(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return ID_LIKE_TOKEN.matcher(question).find();
    }

    private double overlapScore(Set<String> queryTokens, Set<String> docTokens) {
        if (queryTokens == null || queryTokens.isEmpty() || docTokens == null || docTokens.isEmpty()) {
            return 0.0d;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (docTokens.contains(token)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return 0.0d;
        }
        double precision = (double) overlap / (double) docTokens.size();
        double recall = (double) overlap / (double) queryTokens.size();
        double f1 = (2.0d * precision * recall) / (precision + recall);
        double jaccard = (double) overlap / (double) (queryTokens.size() + docTokens.size() - overlap);
        // Blend favors query containment to avoid under-scoring strong business matches
        // when doc token sets are large (common with rich semantic YAML descriptions).
        return (0.50d * recall) + (0.30d * f1) + (0.20d * jaccard);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT));
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank() && part.length() > 1) {
                out.add(part);
            }
        }
        return out;
    }

    private double strongFieldOwnershipScore(Set<String> queryTokens, Map<String, SemanticField> fields) {
        if (queryTokens == null || queryTokens.isEmpty() || fields == null || fields.isEmpty()) {
            return 0.0d;
        }
        int hits = 0;
        for (String fieldName : fields.keySet()) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(fieldName);
            if (!normalized.isBlank() && queryTokens.contains(normalized)) {
                hits++;
                continue;
            }
            String snake = camelToSnake(fieldName);
            if (!snake.isBlank()) {
                Set<String> snakeTokens = tokenize(snake);
                boolean matched = false;
                for (String token : snakeTokens) {
                    if (queryTokens.contains(token)) {
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    hits++;
                }
            }
        }
        if (hits <= 0) {
            return 0.0d;
        }
        return Math.min(1.0d, (double) hits / 2.0d);
    }

    private List<String> reasons(double synonym, double fields, double idPattern, double lexical, double fieldOwnership, double vector, double feedbackBoost) {
        List<String> out = new ArrayList<>();
        if (synonym > 0.0d) out.add("synonym");
        if (fields > 0.0d) out.add("field");
        if (idPattern > 0.0d) out.add("idPattern");
        if (lexical > 0.0d) out.add("lexical");
        if (fieldOwnership > 0.0d) out.add("fieldOwnership");
        if (vector > 0.0d) out.add("vector");
        if (feedbackBoost > 0.0d) out.add("feedbackVector");
        return out;
    }

    private FeedbackBoostResult loadFeedbackBoost(float[] queryVector, ConvEngineMcpConfig.Db.Semantic cfg) {
        try {
            if (cfg == null || cfg.getVector() == null || !cfg.getVector().isEnabled()) {
                return FeedbackBoostResult.empty();
            }
            if (!cfg.getVector().isFeedbackEnabled()) {
                return FeedbackBoostResult.empty();
            }
            if (!"postgres".equalsIgnoreCase(cfg.getSqlDialect())) {
                return FeedbackBoostResult.empty();
            }
            NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
            if (jdbcTemplate == null) {
                return FeedbackBoostResult.empty();
            }
            String table = cfg.getVector().getTable();
            String namespaceCol = cfg.getVector().getNamespaceColumn();
            String typeCol = cfg.getVector().getTargetTypeColumn();
            String embCol = cfg.getVector().getEmbeddingColumn();
            String metadataCol = cfg.getVector().getMetadataColumn();

            String sql = """
                    SELECT %s AS metadata_json,
                           1 - (%s <=> CAST(:queryVec AS vector)) AS score
                    FROM %s
                    WHERE UPPER(%s) = :targetType
                      AND %s = :namespace
                    ORDER BY %s <=> CAST(:queryVec AS vector)
                    LIMIT :maxRows
                    """.formatted(metadataCol, embCol, table, typeCol, namespaceCol, embCol);
            String vecLiteral = toVectorLiteral(queryVector);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, Map.of(
                    "queryVec", vecLiteral,
                    "targetType", FEEDBACK_TARGET_TYPE,
                    "namespace", "user-query-knowledge",
                    "maxRows", Math.max(1, cfg.getVector().getMaxResults())
            ));
            if (rows.isEmpty()) {
                return FeedbackBoostResult.empty();
            }
            Map<String, Double> entityBoostByName = new LinkedHashMap<>();
            Map<String, Double> tableBoostByName = new LinkedHashMap<>();

            for (Map<String, Object> row : rows) {
                double similarity = clamp01(toDouble(row.get("score")));
                if (similarity <= 0.0d) {
                    continue;
                }
                JsonNode metadata = toJsonNode(row.get("metadata_json"));
                if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
                    continue;
                }
                JsonNode observation = metadata.path("observation");
                if (observation.isMissingNode() || observation.isNull()) {
                    continue;
                }
                String entity = firstNonBlank(
                        textAt(observation, "astEntity"),
                        textAt(observation.path("ast"), "entity"),
                        textAt(observation.path("canonicalAst"), "entity")
                );
                if (entity != null) {
                    mergeBoost(entityBoostByName, entity, similarity);
                }
                JsonNode requiredTables = observation.path("joinPath").path("requiredTables");
                if (requiredTables.isArray()) {
                    for (JsonNode node : requiredTables) {
                        String tableName = trimToNull(node.asText(null));
                        if (tableName != null) {
                            mergeBoost(tableBoostByName, tableName, similarity);
                        }
                    }
                }
                JsonNode candidateTables = observation.path("retrieval").path("candidateTables");
                if (candidateTables.isArray()) {
                    for (JsonNode tableNode : candidateTables) {
                        String tableName = trimToNull(tableNode.path("name").asText(null));
                        if (tableName != null) {
                            mergeBoost(tableBoostByName, tableName, similarity);
                        }
                    }
                }
            }
            return new FeedbackBoostResult(entityBoostByName, tableBoostByName);
        } catch (Exception ex) {
            log.debug("feedback vector boost load failed; continuing without feedback boost. cause={}", ex.getMessage());
            return FeedbackBoostResult.empty();
        }
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof JsonNode node) {
                return node;
            }
            return objectMapper.readTree(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private String textAt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return trimToNull(node.path(field).asText(null));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void mergeBoost(Map<String, Double> target, String name, double similarity) {
        if (target == null || name == null) {
            return;
        }
        target.merge(name, clamp01(similarity), Math::max);
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

    private record FeedbackBoostResult(
            Map<String, Double> entityBoostByName,
            Map<String, Double> tableBoostByName
    ) {
        static FeedbackBoostResult empty() {
            return new FeedbackBoostResult(Map.of(), Map.of());
        }
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String camelToSnake(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private double clamp01(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
