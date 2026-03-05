package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.provider;

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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Pattern REQUEST_ID = Pattern.compile("\\bZPR\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DON_ID = Pattern.compile("\\bDON\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCONNECT_ID = Pattern.compile("\\bZPDISC\\d+\\b", Pattern.CASE_INSENSITIVE);

    private final SemanticModelRegistry modelRegistry;
    private final ConvEngineMcpConfig mcpConfig;
    private final LlmClient llmClient;
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
        boolean hasRequestId = REQUEST_ID.matcher(question).find();
        boolean hasDonId = DON_ID.matcher(question).find();
        boolean hasDisconnectId = DISCONNECT_ID.matcher(question).find();

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
        if (qVec != null && qVec.length > 0) {
            SemanticVectorSearchAdapter adapter = resolveVectorAdapter(session);
            if (adapter != null) {
                vectorEntityScores = adapter.entityScores(session, qVec);
                vectorTableScores = adapter.tableScores(session, qVec);
            }
        }

        List<CandidateEntity> entities = new ArrayList<>();
        List<CandidateEntity> scoredEntities = new ArrayList<>();
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            String entityName = entry.getKey();
            SemanticEntity entity = entry.getValue();

            double synonym = overlapScore(tokenSet, tokenize(String.join(" ", entity.synonyms())));
            double fields = fieldScore(tokenSet, entity.fields());
            double idPattern = idPatternScore(entityName, hasRequestId, hasDonId, hasDisconnectId);
            double lexical = overlapScore(tokenSet, tokenize(entity.description()));

            double deterministic = rc.getSynonymWeight() * synonym
                    + rc.getFieldWeight() * fields
                    + rc.getIdPatternWeight() * idPattern
                    + rc.getLexicalWeight() * lexical;
            double vector = vectorEntityScores.getOrDefault(entityName, 0.0d);
            double total = rc.getDeterministicBlendWeight() * clamp01(deterministic)
                    + rc.getVectorBlendWeight() * clamp01(vector);
            CandidateEntity candidate = new CandidateEntity(entityName, round(total), round(deterministic), round(vector),
                    reasons(synonym, fields, idPattern, lexical, vector));
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
                double total = rc.getDeterministicBlendWeight() * clamp01(deterministic)
                        + rc.getVectorBlendWeight() * clamp01(vector);
                CandidateTable candidate = new CandidateTable(tableName, entityName, round(total), round(deterministic), round(vector),
                        reasons(0.0d, 0.0d, 0.0d, deterministic, vector));
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

    private double idPatternScore(String entityName, boolean requestId, boolean donId, boolean disconnectId) {
        String lower = entityName == null ? "" : entityName.toLowerCase(Locale.ROOT);
        double score = 0.0d;
        if (requestId && lower.contains("request")) {
            score += 1.0d;
        }
        if (donId && (lower.contains("disconnect") || lower.contains("order"))) {
            score += 1.0d;
        }
        if (disconnectId && (lower.contains("disconnect") || lower.contains("billing"))) {
            score += 1.0d;
        }
        return Math.min(1.0d, score / 2.0d);
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

    private List<String> reasons(double synonym, double fields, double idPattern, double lexical, double vector) {
        List<String> out = new ArrayList<>();
        if (synonym > 0.0d) out.add("synonym");
        if (fields > 0.0d) out.add("field");
        if (idPattern > 0.0d) out.add("idPattern");
        if (lexical > 0.0d) out.add("lexical");
        if (vector > 0.0d) out.add("vector");
        return out;
    }

    private double clamp01(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
