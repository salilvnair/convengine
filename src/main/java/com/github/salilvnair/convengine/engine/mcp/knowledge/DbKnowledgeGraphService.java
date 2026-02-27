package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
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
public class DbKnowledgeGraphService {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "with", "by",
            "is", "are", "was", "were", "be", "as", "at", "from", "it", "that", "this", "then",
            "how", "what", "when", "why", "who", "where", "show", "find", "get", "give", "tell");

    private final JdbcTemplate jdbcTemplate;
    private final ConvEngineMcpConfig mcpConfig;

    public Map<String, Object> resolveKnowledge(String question) {
        ConvEngineMcpConfig.Db.Knowledge cfg = mcpConfig.getDb().getKnowledge();
        List<String> queryTokens = normalizeTokens(question);

        List<Map<String, Object>> queryRows = readRows(requireSafeIdentifier(cfg.getQueryCatalogTable()), cfg.getScanLimit());
        List<Map<String, Object>> schemaRows = readRows(requireSafeIdentifier(cfg.getSchemaCatalogTable()), cfg.getScanLimit());

        List<Map<String, Object>> rankedQueryKnowledge = rankQueryKnowledge(queryRows, queryTokens, cfg);
        List<Map<String, Object>> rankedSchemaKnowledge = rankSchemaKnowledge(schemaRows, queryTokens, cfg);

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
        return response;
    }

    private List<Map<String, Object>> rankQueryKnowledge(
            List<Map<String, Object>> rows,
            List<String> queryTokens,
            ConvEngineMcpConfig.Db.Knowledge cfg) {

        String queryTextCol = requireSafeIdentifier(cfg.getQueryTextColumn()).toLowerCase(Locale.ROOT);
        String descriptionCol = requireSafeIdentifier(cfg.getQueryDescriptionColumn()).toLowerCase(Locale.ROOT);
        String preparedSqlCol = requireSafeIdentifier(cfg.getPreparedSqlColumn()).toLowerCase(Locale.ROOT);
        String tagsCol = requireSafeIdentifier(cfg.getTagsColumn()).toLowerCase(Locale.ROOT);
        String apiHintsCol = requireSafeIdentifier(cfg.getApiHintsColumn()).toLowerCase(Locale.ROOT);

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String queryText = asText(row.get(queryTextCol));
            String description = asText(row.get(descriptionCol));
            String preparedSql = asText(row.get(preparedSqlCol));
            String tags = asText(row.get(tagsCol));
            String apiHints = asText(row.get(apiHintsCol));

            double score = score(queryTokens, normalizeTokens(String.join(" ", queryText, description, tags, apiHints)));
            if (score < cfg.getMinScore()) {
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
        return scored.stream().limit(cfg.getMaxResults()).toList();
    }

    private List<Map<String, Object>> rankSchemaKnowledge(
            List<Map<String, Object>> rows,
            List<String> queryTokens,
            ConvEngineMcpConfig.Db.Knowledge cfg) {

        String tableCol = requireSafeIdentifier(cfg.getSchemaTableNameColumn()).toLowerCase(Locale.ROOT);
        String columnCol = requireSafeIdentifier(cfg.getSchemaColumnNameColumn()).toLowerCase(Locale.ROOT);
        String descriptionCol = requireSafeIdentifier(cfg.getSchemaDescriptionColumn()).toLowerCase(Locale.ROOT);
        String tagsCol = requireSafeIdentifier(cfg.getSchemaTagsColumn()).toLowerCase(Locale.ROOT);

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String tableName = asText(row.get(tableCol));
            String columnName = asText(row.get(columnCol));
            String description = asText(row.get(descriptionCol));
            String tags = asText(row.get(tagsCol));

            double score = score(queryTokens, normalizeTokens(String.join(" ", tableName, columnName, description, tags)));
            if (score < cfg.getMinScore()) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", round(score));
            item.put("tableName", tableName);
            item.put("columnName", columnName);
            item.put("description", description);
            item.put("tags", splitTags(tags));
            scored.add(item);
        }

        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("score")).reversed());
        return scored.stream().limit(cfg.getMaxResults()).toList();
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

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
