package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DbkgSupportService {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern STEP_REF_PATTERN = Pattern.compile("^([A-Za-z0-9_]+)(?:\\[(\\d+)])?(?:\\.(.+))?$");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "with", "by",
            "is", "are", "was", "were", "be", "as", "at", "from", "it", "that", "this", "then",
            "how", "what", "when", "why", "who", "where", "show", "find", "get", "give", "tell");

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConvEngineMcpConfig.Db.KnowledgeGraph cfg() {
        return mcpConfig.getDb().getKnowledgeGraph();
    }

    public String extractQuestion(Map<String, Object> args, EngineSession session) {
        if (args != null) {
            for (String key : List.of("question", "query", "user_input")) {
                Object value = args.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        return session == null ? "" : asText(session.getUserText());
    }

    public List<Map<String, Object>> readEnabledRows(String tableName) {
        String safeTable = requireSafeIdentifier(tableName);
        String sql = "SELECT * FROM " + safeTable + " WHERE enabled = :enabled";
        List<Map<String, Object>> rows;
        try {
            rows = namedParameterJdbcTemplate.queryForList(sql, Map.of("enabled", true));
        } catch (Exception e) {
            rows = namedParameterJdbcTemplate.queryForList(sql, Map.of("enabled", 1));
        }
        return limitRows(rows);
    }

    public List<Map<String, Object>> readEnabledRowsOptional(String tableName) {
        try {
            return readEnabledRows(tableName);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> limitRows(List<Map<String, Object>> rows) {
        if (rows.size() <= Math.max(1, cfg().getScanLimit())) {
            return normalizeRows(rows);
        }
        return normalizeRows(rows.subList(0, cfg().getScanLimit()));
    }

    public List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                item.put(entry.getKey().toLowerCase(Locale.ROOT), normalizeValue(entry.getValue()));
            }
            normalized.add(item);
        }
        return normalized;
    }

    public List<Map<String, Object>> normalizeRowValues(List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                item.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            normalized.add(item);
        }
        return normalized;
    }

    public Object normalizeValue(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        Object pgValue = extractPgObjectValue(value);
        if (pgValue != null) {
            return pgValue;
        }
        return value;
    }

    private Object extractPgObjectValue(Object value) {
        if (value == null) {
            return null;
        }
        if (!"org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            return null;
        }
        try {
            java.lang.reflect.Method getValue = value.getClass().getMethod("getValue");
            Object out = getValue.invoke(value);
            return out == null ? null : String.valueOf(out);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public java.util.Optional<Map<String, Object>> findRowByKey(String tableName, String keyColumn, String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        return readEnabledRows(tableName).stream()
                .filter(row -> value.equalsIgnoreCase(asText(row.get(keyColumn.toLowerCase(Locale.ROOT)))))
                .findFirst();
    }

    public List<String> normalizeTokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = TOKEN_SPLIT.split(value.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank() || part.length() < 2 || STOP_WORDS.contains(part)) {
                continue;
            }
            out.add(part);
        }
        return out;
    }

    public double score(List<String> queryTokens, List<String> docTokens) {
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

    public List<Map<String, Object>> rankTextRows(
            List<Map<String, Object>> rows,
            List<String> tokens,
            List<String> textColumns,
            List<String> outputColumns,
            String scoreField) {

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> docTokens = new ArrayList<>();
            for (String column : textColumns) {
                docTokens.addAll(normalizeTokens(asText(row.get(column))));
            }
            double score = score(tokens, docTokens);
            if (score <= 0.0d) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            for (String column : outputColumns) {
                item.put(toCamelCase(column), row.get(column));
            }
            item.put(scoreField, round(score));
            ranked.add(item);
        }
        ranked.sort(Comparator.comparingDouble(row -> -parseDouble(row.get(scoreField), 0.0d)));
        int max = Math.max(1, cfg().getMaxResults());
        return ranked.size() <= max ? ranked : ranked.subList(0, max);
    }

    public List<Map<String, Object>> rankSignals(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> signals,
            String idColumn,
            String question,
            List<String> tokens) {

        Map<String, List<Map<String, Object>>> signalsByCandidate = new LinkedHashMap<>();
        for (Map<String, Object> signal : signals) {
            String key = asText(signal.get(idColumn));
            if (key.isBlank()) {
                continue;
            }
            signalsByCandidate.computeIfAbsent(key, ignored -> new ArrayList<>()).add(signal);
        }

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String candidateKey = asText(candidate.get(idColumn));
            List<Map<String, Object>> candidateSignals = signalsByCandidate.getOrDefault(candidateKey, List.of());
            double score = 0.0d;
            boolean requiredMiss = false;
            List<Map<String, Object>> matchedSignals = new ArrayList<>();

            for (Map<String, Object> signal : candidateSignals) {
                boolean matched = matchesSignal(signal, question, tokens);
                if (matched) {
                    score += parseDouble(signal.get("weight"), 1.0d);
                    matchedSignals.add(Map.of(
                            "signalType", asText(signal.get("signal_type")),
                            "matchValue", asText(signal.get("match_value")),
                            "weight", parseDouble(signal.get("weight"), 1.0d)));
                } else if (truthy(signal.get("required_flag"))) {
                    requiredMiss = true;
                }
            }

            if (requiredMiss || score <= 0.0d) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            if ("case_code".equalsIgnoreCase(idColumn)) {
                item.put("caseCode", candidateKey);
                item.put("caseName", asText(candidate.get("case_name")));
                item.put("description", asText(candidate.get("description")));
            } else {
                item.put("playbookCode", candidateKey);
                item.put("playbookName", asText(candidate.get("playbook_name")));
                item.put("description", asText(candidate.get("description")));
                item.put("caseCode", asText(candidate.get("case_code")));
            }
            item.put("score", round(score));
            item.put("matchedSignals", matchedSignals);
            ranked.add(item);
        }

        ranked.sort(Comparator.comparingDouble(row -> -parseDouble(row.get("score"), 0.0d)));
        int max = Math.max(1, cfg().getMaxResults());
        return ranked.size() <= max ? ranked : ranked.subList(0, max);
    }

    public boolean matchesSignal(Map<String, Object> signal, String question, List<String> tokens) {
        String operator = asText(signal.get("match_operator"));
        String matchValue = asText(signal.get("match_value"));
        if (matchValue.isBlank()) {
            return false;
        }
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String m = matchValue.toLowerCase(Locale.ROOT);
        if ("EXACT".equalsIgnoreCase(operator)) {
            return q.equals(m);
        }
        if ("TOKEN".equalsIgnoreCase(operator)) {
            return tokens.contains(m);
        }
        return q.contains(m);
    }

    public Map<String, Object> normalizePlaybook(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("playbookCode", asText(row.get("playbook_code")));
        item.put("playbookName", asText(row.get("playbook_name")));
        item.put("description", asText(row.get("description")));
        item.put("caseCode", asText(row.get("case_code")));
        return item;
    }

    public List<Map<String, Object>> stepsForPlaybook(String playbookCode) {
        return readEnabledRows(cfg().getPlaybookStepTable()).stream()
                .filter(row -> playbookCode.equalsIgnoreCase(asText(row.get("playbook_code"))))
                .sorted(Comparator.comparing(row -> parseInt(row.get("sequence_no"), 0)))
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stepCode", row.get("step_code"));
                    item.put("stepType", row.get("step_type"));
                    item.put("executorCode", row.get("executor_code"));
                    item.put("templateCode", row.get("template_code"));
                    item.put("configJson", row.get("config_json"));
                    item.put("sequenceNo", row.get("sequence_no"));
                    item.put("haltOnError", row.get("halt_on_error"));
                    return item;
                })
                .toList();
    }

    public List<Map<String, Object>> transitionsForPlaybook(String playbookCode) {
        return readEnabledRows(cfg().getPlaybookTransitionTable()).stream()
                .filter(row -> playbookCode.equalsIgnoreCase(asText(row.get("playbook_code"))))
                .sorted(Comparator.comparing(row -> parseInt(row.get("priority"), 100)))
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("fromStepCode", row.get("from_step_code"));
                    item.put("outcomeCode", row.get("outcome_code"));
                    item.put("toStepCode", row.get("to_step_code"));
                    item.put("conditionExpr", row.get("condition_expr"));
                    item.put("priority", row.get("priority"));
                    return item;
                })
                .toList();
    }

    public Map<String, Object> resolveQueryParams(String templateCode, Map<String, Object> runtime) {
        List<Map<String, Object>> rules = readEnabledRows(cfg().getQueryParamRuleTable()).stream()
                .filter(row -> templateCode.equalsIgnoreCase(asText(row.get("query_code"))))
                .toList();

        Map<String, Object> params = new LinkedHashMap<>();
        for (Map<String, Object> rule : rules) {
            String paramName = asText(rule.get("param_name"));
            Object value = resolveParamValue(rule, runtime);
            if (value == null && truthy(rule.get("required_flag"))) {
                value = rule.get("default_value");
            }
            params.put(paramName, value);
        }
        return params;
    }

    public Object resolveParamValue(Map<String, Object> rule, Map<String, Object> runtime) {
        String sourceType = asText(rule.get("source_type"));
        String sourceKey = asText(rule.get("source_key"));
        Object defaultValue = rule.get("default_value");
        Object value = switch (sourceType.toUpperCase(Locale.ROOT)) {
            case "DEFAULT" -> defaultValue;
            case "CASE_CONTEXT" -> extractFromRuntime(sourceKey, runtime, true);
            case "DERIVED_CONTEXT" -> extractFromRuntime(sourceKey, runtime, false);
            case "PREV_STEP_OUTPUT" -> extractPreviousStepValue(sourceKey, runtime);
            case "STATUS_DICTIONARY" -> lookupStatusCode(sourceKey, defaultValue);
            default -> null;
        };
        if (value == null) {
            value = defaultValue;
        }
        return applyTransform(value, asText(rule.get("transform_rule")));
    }

    public Object extractPreviousStepValue(String ref, Map<String, Object> runtime) {
        Matcher matcher = STEP_REF_PATTERN.matcher(ref);
        if (!matcher.matches()) {
            return null;
        }
        String stepCode = matcher.group(1);
        int index = matcher.group(2) == null ? -1 : parseInt(matcher.group(2), -1);
        String path = matcher.group(3);
        Object base = stepOutputs(runtime).get(stepCode);
        if (!(base instanceof Map<?, ?> baseMap)) {
            return null;
        }
        Object candidate = ((Map<?, ?>) baseMap).get("rows");
        if (index >= 0 && candidate instanceof List<?> list) {
            if (index >= list.size()) {
                return null;
            }
            candidate = list.get(index);
        } else if (path == null) {
            return baseMap;
        }
        if (path == null || path.isBlank()) {
            return candidate;
        }
        return extractByPath(candidate, path);
    }

    public Object lookupStatusCode(String sourceKey, Object defaultValue) {
        String[] parts = sourceKey.split("\\.");
        if (parts.length != 3) {
            return defaultValue;
        }
        String dictionaryName = parts[0];
        String fieldName = parts[1];
        String label = parts[2];
        return readEnabledRows(cfg().getStatusDictionaryTable()).stream()
                .filter(row -> dictionaryName.equalsIgnoreCase(asText(row.get("dictionary_name"))))
                .filter(row -> fieldName.equalsIgnoreCase(asText(row.get("field_name"))))
                .filter(row -> label.equalsIgnoreCase(asText(row.get("code_label"))))
                .map(row -> row.get("code_value"))
                .findFirst()
                .orElse(defaultValue);
    }

    public Object extractFromRuntime(String path, Map<String, Object> runtime, boolean checkArgsFirst) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (checkArgsFirst) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) runtime.getOrDefault("args", Map.of());
            Object direct = extractByPath(args, path);
            if (direct != null) {
                return direct;
            }
        }
        return extractByPath(runtime, path);
    }

    public Object extractByPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            Matcher matcher = Pattern.compile("^([A-Za-z0-9_]+)(?:\\[(\\d+)])?$").matcher(part);
            if (!matcher.matches()) {
                return null;
            }
            String key = matcher.group(1);
            Integer index = matcher.group(2) == null ? null : parseInt(matcher.group(2), -1);
            if (current instanceof Map<?, ?> map) {
                current = ((Map<?, ?>) map).get(key);
            } else {
                return null;
            }
            if (index != null) {
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            }
        }
        return current;
    }

    public Object applyTransform(Object value, String transformRule) {
        if (value == null || transformRule == null || transformRule.isBlank()) {
            return value;
        }
        return switch (transformRule.toUpperCase(Locale.ROOT)) {
            case "TO_INTEGER" -> parseInt(value, 0);
            case "TRIM" -> asText(value).trim();
            default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> stepOutputs(Map<String, Object> runtime) {
        return (Map<String, Object>) runtime.computeIfAbsent("stepOutputs", ignored -> new LinkedHashMap<String, Object>());
    }

    public Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    public String requireSafeIdentifier(String input) {
        if (input == null || input.isBlank() || !SAFE_SQL_IDENTIFIER.matcher(input).matches()) {
            throw new IllegalStateException("Unsafe or blank DBKG identifier: " + input);
        }
        return input;
    }

    public String normalizeKey(String value) {
        return asText(value).trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeColumnKey(String objectName, String columnName) {
        if (objectName == null || objectName.isBlank() || columnName == null || columnName.isBlank()) {
            return "";
        }
        return normalizeKey(objectName) + "." + normalizeKey(columnName);
    }

    public Set<String> normalizeSchemaNames(List<String> schemas) {
        Set<String> normalized = new LinkedHashSet<>();
        if (schemas == null) {
            return normalized;
        }
        for (String schema : schemas) {
            if (schema != null && !schema.isBlank()) {
                normalized.add(schema.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    public boolean isAllowedSchema(String schemaName, Set<String> included, Set<String> excluded) {
        String normalized = schemaName == null ? "" : schemaName.toLowerCase(Locale.ROOT);
        if (!included.isEmpty() && !included.contains(normalized)) {
            return false;
        }
        return !excluded.contains(normalized);
    }

    public String safeSchema(String schema) {
        return schema == null ? "" : schema;
    }

    public String toLowerSnake(String input) {
        return asText(input).toLowerCase(Locale.ROOT);
    }

    public void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = asText(value);
        if (!text.isBlank()) {
            target.put(key, value);
        }
    }

    public String toCamelCase(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] parts = input.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            out.append(Character.toUpperCase(parts[i].charAt(0)));
            out.append(parts[i].substring(1));
        }
        return out.toString();
    }

    public String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public double parseDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    public String stripQuotes(String input) {
        if (input == null || input.length() < 2) {
            return input;
        }
        if ((input.startsWith("'") && input.endsWith("'")) || (input.startsWith("\"") && input.endsWith("\""))) {
            return input.substring(1, input.length() - 1);
        }
        return input;
    }

    public double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
