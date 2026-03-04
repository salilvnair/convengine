package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DbSemanticCatalogService {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "with", "by",
            "is", "are", "was", "were", "be", "as", "at", "from", "it", "that", "this", "then",
            "how", "what", "when", "why", "who", "where", "show", "find", "get", "give", "tell");

    private final JdbcTemplate jdbcTemplate;
    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<List<SemanticCatalogVectorSearchInterceptor>> vectorSearchInterceptorsProvider;

    public Map<String, Object> resolveKnowledge(String question) {
        ConvEngineMcpConfig.Db.Knowledge cfg = mcpConfig.getDb().semanticCatalogConfig();
        List<String> queryTokens = normalizeTokens(question);
        ConvEngineMcpConfig.Db.Knowledge.VectorSearch vectorCfg = cfg.getVectorSearch() == null
                ? new ConvEngineMcpConfig.Db.Knowledge.VectorSearch()
                : cfg.getVectorSearch();

        List<Map<String, Object>> queryRows = cfg.isQueryKnowledge()
                ? readRows(requireSafeIdentifier(cfg.getQueryCatalogTable()), cfg.getScanLimit())
                : List.of();
        List<Map<String, Object>> schemaRows = cfg.isSchemaKnowledge()
                ? readRows(requireSafeIdentifier(cfg.getSchemaCatalogTable()), cfg.getScanLimit())
                : List.of();
        List<Map<String, Object>> vectorRankedQueryRows = queryRows;
        List<Map<String, Object>> vectorRankedSchemaRows = schemaRows;
        if (vectorCfg.isEnabled()) {
            SemanticCatalogVectorSearchInterceptor interceptor = resolveVectorSearchInterceptor(vectorCfg);
            if (interceptor != null) {
                vectorRankedQueryRows = cfg.isQueryKnowledge()
                        ? nonEmptyOrFallback(interceptor.rank("query", question, queryRows, cfg), queryRows)
                        : List.of();
                vectorRankedSchemaRows = cfg.isSchemaKnowledge()
                        ? nonEmptyOrFallback(interceptor.rank("schema", question, schemaRows, cfg), schemaRows)
                        : List.of();
            }
        }

        List<Map<String, Object>> rankedQueryKnowledge = cfg.isQueryKnowledge()
                ? rankQueryKnowledge(vectorRankedQueryRows, queryTokens, cfg, vectorCfg)
                : List.of();
        List<Map<String, Object>> rankedSchemaKnowledge = cfg.isSchemaKnowledge()
                ? rankSchemaKnowledge(vectorRankedSchemaRows, queryTokens, cfg, vectorCfg)
                : List.of();

        Map<String, Object> insights = new LinkedHashMap<>();
        insights.put("suggestedPreparedQueries", rankedQueryKnowledge.stream()
                .map(item -> item.get("preparedSql"))
                .filter(v -> v instanceof String && !((String) v).isBlank())
                .distinct()
                .toList());
        insights.put("suggestedTables", rankedSchemaKnowledge.stream()
                .map(item -> item.get("tableName"))
                .filter(v -> v instanceof String && !((String) v).isBlank())
                .distinct()
                .toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question == null ? "" : question);
        response.put("queryKnowledge", rankedQueryKnowledge);
        response.put("schemaKnowledge", rankedSchemaKnowledge);
        response.put("insights", insights);
        if (cfg.isKnowledgeCapsule()) {
            response.put("dbkgCapsule", buildKnowledgeCapsule(question, queryRows, schemaRows, rankedQueryKnowledge, rankedSchemaKnowledge));
        }
        response.put("features", Map.of(
                "knowledgeCapsule", cfg.isKnowledgeCapsule(),
                "queryKnowledge", cfg.isQueryKnowledge(),
                "schemaKnowledge", cfg.isSchemaKnowledge(),
                "vectorSearch", vectorCfg.isEnabled()));
        return response;
    }

    private List<Map<String, Object>> rankQueryKnowledge(
            List<Map<String, Object>> rows,
            List<String> queryTokens,
            ConvEngineMcpConfig.Db.Knowledge cfg,
            ConvEngineMcpConfig.Db.Knowledge.VectorSearch vectorCfg) {

        String queryTextCol = requireSafeIdentifier(cfg.getQueryTextColumn()).toLowerCase(Locale.ROOT);
        String descriptionCol = requireSafeIdentifier(cfg.getQueryDescriptionColumn()).toLowerCase(Locale.ROOT);
        String preparedSqlCol = requireSafeIdentifier(cfg.getPreparedSqlColumn()).toLowerCase(Locale.ROOT);
        String tagsCol = requireSafeIdentifier(cfg.getTagsColumn()).toLowerCase(Locale.ROOT);
        String apiHintsCol = requireSafeIdentifier(cfg.getApiHintsColumn()).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> scored = new ArrayList<>();
        boolean vectorMode = vectorCfg.isEnabled();
        for (Map<String, Object> row : rows) {
            String queryText = asText(row.get(queryTextCol));
            String description = asText(row.get(descriptionCol));
            String preparedSql = asText(row.get(preparedSqlCol));
            String tags = asText(row.get(tagsCol));
            String apiHints = asText(row.get(apiHintsCol));
            double vectorScore = vectorMode ? extractVectorScore(row) : 0.0d;
            double lexicalScore = score(queryTokens, normalizeTokens(String.join(" ", queryText, description, tags, apiHints)));
            double score = vectorMode ? blendScore(vectorScore, lexicalScore) : lexicalScore;

            if (!vectorMode && score < cfg.getMinScore()) {
                continue;
            }
            if (vectorMode && score <= 0.0d) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", round(score));
            item.put("queryText", queryText);
            item.put("description", description);
            item.put("preparedSql", preparedSql);
            item.put("tags", splitTags(tags));
            item.put("apiHints", splitTags(apiHints));
            scored.add(item);
        }

        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("score")).reversed());
        int limit = vectorMode ? Math.max(1, vectorCfg.getMaxResults()) : cfg.getMaxResults();
        return scored.stream().limit(limit).toList();
    }

    private List<Map<String, Object>> rankSchemaKnowledge(
            List<Map<String, Object>> rows,
            List<String> queryTokens,
            ConvEngineMcpConfig.Db.Knowledge cfg,
            ConvEngineMcpConfig.Db.Knowledge.VectorSearch vectorCfg) {

        String tableCol = requireSafeIdentifier(cfg.getSchemaTableNameColumn()).toLowerCase(Locale.ROOT);
        String columnCol = requireSafeIdentifier(cfg.getSchemaColumnNameColumn()).toLowerCase(Locale.ROOT);
        String descriptionCol = requireSafeIdentifier(cfg.getSchemaDescriptionColumn()).toLowerCase(Locale.ROOT);
        String tagsCol = requireSafeIdentifier(cfg.getSchemaTagsColumn()).toLowerCase(Locale.ROOT);
        String validValuesCol = requireSafeIdentifier(cfg.getSchemaValidValuesColumn()).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> scored = new ArrayList<>();
        boolean vectorMode = vectorCfg.isEnabled();
        for (Map<String, Object> row : rows) {
            String tableName = asText(row.get(tableCol));
            String columnName = asText(row.get(columnCol));
            String description = asText(row.get(descriptionCol));
            String tags = asText(row.get(tagsCol));
            String validValues = asText(row.get(validValuesCol));
            double vectorScore = vectorMode ? extractVectorScore(row) : 0.0d;
            double lexicalScore = score(queryTokens, normalizeTokens(String.join(" ", tableName, columnName, description, tags, validValues)));
            double score = vectorMode ? blendScore(vectorScore, lexicalScore) : lexicalScore;

            if (!vectorMode && score < cfg.getMinScore()) {
                continue;
            }
            if (vectorMode && score <= 0.0d) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", round(score));
            item.put("tableName", tableName);
            item.put("columnName", columnName);
            item.put("description", description);
            item.put("tags", splitTags(tags));
            item.put("validValues", splitTags(validValues));
            scored.add(item);
        }

        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("score")).reversed());
        int limit = vectorMode ? Math.max(1, vectorCfg.getMaxResults()) : cfg.getMaxResults();
        return scored.stream().limit(limit).toList();
    }

    private List<Map<String, Object>> readRows(String tableName, int limit) {
        String sql = "SELECT * FROM " + tableName;
        return jdbcTemplate.query(sql, (ResultSetExtractor<List<Map<String, Object>>>) rs -> extractRows(rs, limit));
    }

    private List<Map<String, Object>> extractRows(ResultSet rs, int limit) throws java.sql.SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();

        int count = 0;
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= cols; i++) {
                String key = meta.getColumnLabel(i);
                if (key != null) {
                    row.put(key.toLowerCase(Locale.ROOT), rs.getObject(i));
                }
            }
            out.add(row);
            count++;
            if (count >= Math.max(limit, 1)) {
                break;
            }
        }
        return out;
    }

    private double score(List<String> queryTokens, List<String> docTokens) {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) {
            return 0.0d;
        }
        Set<String> q = new HashSet<>(queryTokens);
        Set<String> d = new HashSet<>(docTokens);

        int overlap = 0;
        for (String token : q) {
            if (d.contains(token)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return 0.0d;
        }
        double precision = (double) overlap / (double) d.size();
        double recall = (double) overlap / (double) q.size();
        return (2.0d * precision * recall) / (precision + recall);
    }

    private List<String> normalizeTokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String[] parts = TOKEN_SPLIT.split(lower);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank() || p.length() < 2 || STOP_WORDS.contains(p)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,|]");
        List<String> tags = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (!t.isBlank()) {
                tags.add(t);
            }
        }
        return tags;
    }

    private String requireSafeIdentifier(String input) {
        if (input == null || input.isBlank() || !SAFE_SQL_IDENTIFIER.matcher(input).matches()) {
            throw new IllegalStateException("Unsafe or blank DB knowledge identifier: " + input);
        }
        return input;
    }

    private SemanticCatalogVectorSearchInterceptor resolveVectorSearchInterceptor(
            ConvEngineMcpConfig.Db.Knowledge.VectorSearch vectorCfg) {
        List<SemanticCatalogVectorSearchInterceptor> interceptors = vectorSearchInterceptorsProvider.getIfAvailable(List::of);
        if (interceptors == null || interceptors.isEmpty()) {
            return null;
        }
        return interceptors.stream()
                .filter(i -> i != null && i.supports(vectorCfg))
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> nonEmptyOrFallback(List<Map<String, Object>> preferred, List<Map<String, Object>> fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }

    private double extractVectorScore(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return 0.0d;
        }
        Object score = row.get("_vector_score");
        if (score == null) {
            score = row.get("vectorScore");
        }
        if (score == null) {
            score = row.get("score");
        }
        if (score instanceof Number n) {
            return Math.max(0.0d, Math.min(1.0d, n.doubleValue()));
        }
        if (score != null) {
            try {
                return Math.max(0.0d, Math.min(1.0d, Double.parseDouble(String.valueOf(score))));
            } catch (Exception ignored) {
            }
        }
        return 0.0d;
    }

    private double blendScore(double vectorScore, double lexicalScore) {
        double v = Math.max(0.0d, Math.min(1.0d, vectorScore));
        double l = Math.max(0.0d, Math.min(1.0d, lexicalScore));
        return (0.80d * v) + (0.20d * l);
    }

    private Map<String, Object> buildKnowledgeCapsule(
            String question,
            List<Map<String, Object>> queryRows,
            List<Map<String, Object>> schemaRows,
            List<Map<String, Object>> rankedQueryKnowledge,
            List<Map<String, Object>> rankedSchemaKnowledge) {

        ConvEngineMcpConfig.Db.Knowledge cfg = mcpConfig.getDb().semanticCatalogConfig();
        String tableCol = requireSafeIdentifier(cfg.getSchemaTableNameColumn()).toLowerCase(Locale.ROOT);
        String columnCol = requireSafeIdentifier(cfg.getSchemaColumnNameColumn()).toLowerCase(Locale.ROOT);
        String validValuesCol = requireSafeIdentifier(cfg.getSchemaValidValuesColumn()).toLowerCase(Locale.ROOT);
        String queryTextCol = requireSafeIdentifier(cfg.getQueryTextColumn()).toLowerCase(Locale.ROOT);
        String preparedSqlCol = requireSafeIdentifier(cfg.getPreparedSqlColumn()).toLowerCase(Locale.ROOT);

        Map<String, List<String>> columnsByObject = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> validValuesByObject = new LinkedHashMap<>();
        for (Map<String, Object> row : schemaRows) {
            String table = asText(row.get(tableCol)).trim();
            String column = asText(row.get(columnCol)).trim();
            String validValues = asText(row.get(validValuesCol)).trim();
            if (table.isBlank()) {
                continue;
            }
            columnsByObject.computeIfAbsent(table, k -> new ArrayList<>());
            if (!column.isBlank() && !columnsByObject.get(table).contains(column)) {
                columnsByObject.get(table).add(column);
            }
            if (!column.isBlank() && !validValues.isBlank()) {
                validValuesByObject.computeIfAbsent(table, k -> new LinkedHashMap<>());
                validValuesByObject.get(table).put(column, splitTags(validValues));
            }
        }

        List<String> objects = new ArrayList<>(columnsByObject.keySet());
        objects.sort(String::compareToIgnoreCase);
        columnsByObject.replaceAll((k, v) -> v.stream().distinct().sorted(String::compareToIgnoreCase).toList());

        List<Map<String, Object>> queryTemplates = queryRows.stream()
                .map(row -> {
                    String text = asText(row.get(queryTextCol)).trim();
                    String preparedSql = asText(row.get(preparedSqlCol)).trim();
                    if (text.isBlank() && preparedSql.isBlank()) {
                        return null;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    if (!text.isBlank()) {
                        item.put("queryText", text);
                    }
                    if (!preparedSql.isBlank()) {
                        item.put("preparedSql", preparedSql);
                    }
                    return item;
                })
                .filter(v -> v != null)
                .limit(200)
                .toList();

        Map<String, Object> sourceCoverage = new LinkedHashMap<>();
        sourceCoverage.put("ce_mcp_query_knowledge", queryRows.size());
        sourceCoverage.put("ce_mcp_schema_knowledge", schemaRows.size());

        Map<String, Object> sqlGraph = new LinkedHashMap<>();
        sqlGraph.put("objects", objects);
        sqlGraph.put("columnsByObject", columnsByObject);
        sqlGraph.put("validValuesByObject", validValuesByObject);
        sqlGraph.put("queryTemplates", queryTemplates);
        sqlGraph.put("joinPaths", List.of());
        sqlGraph.put("statusDictionary", List.of());
        sqlGraph.put("lineage", List.of());

        Map<String, Object> semanticGraph = new LinkedHashMap<>();
        semanticGraph.put("entities", objects);
        semanticGraph.put("cases", List.of());
        semanticGraph.put("playbooks", List.of());
        semanticGraph.put("systems", List.of());
        semanticGraph.put("apiFlows", List.of());

        Map<String, Object> plannerRuntime = new LinkedHashMap<>();
        plannerRuntime.put("question", question == null ? "" : question);
        plannerRuntime.put("suggestedTables", rankedSchemaKnowledge.stream()
                .map(item -> item.get("tableName"))
                .filter(v -> v instanceof String && !((String) v).isBlank())
                .distinct()
                .toList());
        plannerRuntime.put("suggestedPreparedQueries", rankedQueryKnowledge.stream()
                .map(item -> item.get("preparedSql"))
                .filter(v -> v instanceof String && !((String) v).isBlank())
                .distinct()
                .toList());
        plannerRuntime.put("hints", List.of("semantic-catalog capsule"));

        Map<String, Object> capsule = new LinkedHashMap<>();
        capsule.put("version", "semantic-catalog-capsule-v2");
        capsule.put("source", "db.semantic.catalog");
        capsule.put("sourceCoverage", sourceCoverage);
        capsule.put("sqlGraph", sqlGraph);
        capsule.put("semanticGraph", semanticGraph);
        capsule.put("plannerRuntime", plannerRuntime);
        return capsule;
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
