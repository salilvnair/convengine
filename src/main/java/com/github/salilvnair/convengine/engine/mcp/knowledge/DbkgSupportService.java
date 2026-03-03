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
                Object val = row.get(column);
                if (val instanceof String text) {
                    text = text.trim();
                    if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
                        try {
                            val = mapper.readTree(text);
                        } catch (Exception ignored) {
                        }
                    }
                }
                item.put(toCamelCase(column), val);
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
        return (Map<String, Object>) runtime.computeIfAbsent("stepOutputs",
                ignored -> new LinkedHashMap<String, Object>());
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

    public Map<String, Object> buildMetadataCapsule(
            String question,
            List<String> tokens,
            Map<String, Object> selectedCase,
            Map<String, Object> selectedPlaybook) {
        ConvEngineMcpConfig.Db.KnowledgeGraph cfg = cfg();

        List<Map<String, Object>> caseTypes = readEnabledRowsOptional(cfg.getCaseTypeTable());
        List<Map<String, Object>> caseSignals = readEnabledRowsOptional(cfg.getCaseSignalTable());
        List<Map<String, Object>> playbooks = readEnabledRowsOptional(cfg.getPlaybookTable());
        List<Map<String, Object>> playbookSignals = readEnabledRowsOptional(cfg.getPlaybookSignalTable());
        List<Map<String, Object>> domainEntities = readEnabledRowsOptional(cfg.getDomainEntityTable());
        List<Map<String, Object>> domainRelations = readEnabledRowsOptional(cfg.getDomainRelationTable());
        List<Map<String, Object>> systems = readEnabledRowsOptional(cfg.getSystemNodeTable());
        List<Map<String, Object>> systemRelations = readEnabledRowsOptional(cfg.getSystemRelationTable());
        List<Map<String, Object>> apiFlows = readEnabledRowsOptional(cfg.getApiFlowTable());
        List<Map<String, Object>> dbObjects = readEnabledRowsOptional(cfg.getDbObjectTable());
        List<Map<String, Object>> dbColumns = readEnabledRowsOptional(cfg.getDbColumnTable());
        List<Map<String, Object>> dbJoinPaths = readEnabledRowsOptional(cfg.getDbJoinPathTable());
        List<Map<String, Object>> statuses = readEnabledRowsOptional(cfg.getStatusDictionaryTable());
        List<Map<String, Object>> lineages = readEnabledRowsOptional(cfg.getIdLineageTable());
        List<Map<String, Object>> executorTemplates = readEnabledRowsOptional("ce_mcp_executor_template");
        List<Map<String, Object>> queryTemplates = readEnabledRowsOptional(cfg.getQueryTemplateTable());
        List<Map<String, Object>> queryParamRules = readEnabledRowsOptional(cfg.getQueryParamRuleTable());
        List<Map<String, Object>> playbookSteps = readEnabledRowsOptional(cfg.getPlaybookStepTable());
        List<Map<String, Object>> playbookTransitions = readEnabledRowsOptional(cfg.getPlaybookTransitionTable());
        List<Map<String, Object>> outcomeRules = readEnabledRowsOptional(cfg.getOutcomeRuleTable());
        List<Map<String, Object>> sqlGuardrails = readEnabledRowsOptional(mcpConfig.getDb().getSqlGuardrailTable());
        List<Map<String, Object>> mcpTools = readEnabledRowsOptional("ce_mcp_tool");
        List<Map<String, Object>> mcpPlanners = readEnabledRowsOptional("ce_mcp_planner");

        Map<String, Object> capsule = new LinkedHashMap<>();
        capsule.put("version", "dbkg-capsule-v1");
        capsule.put("question", question == null ? "" : question);
        capsule.put("tokens", tokens == null ? List.of() : tokens.stream().distinct().limit(12).toList());
        capsule.put("selectedCaseCode", asText(selectedCase == null ? null : selectedCase.get("caseCode")));
        capsule.put("selectedPlaybookCode", asText(selectedPlaybook == null ? null : selectedPlaybook.get("playbookCode")));

        Map<String, Object> sourceCoverage = new LinkedHashMap<>();
        sourceCoverage.put(cfg.getCaseTypeTable(), caseTypes.size());
        sourceCoverage.put(cfg.getCaseSignalTable(), caseSignals.size());
        sourceCoverage.put(cfg.getPlaybookTable(), playbooks.size());
        sourceCoverage.put(cfg.getPlaybookSignalTable(), playbookSignals.size());
        sourceCoverage.put(cfg.getDomainEntityTable(), domainEntities.size());
        sourceCoverage.put(cfg.getDomainRelationTable(), domainRelations.size());
        sourceCoverage.put(cfg.getSystemNodeTable(), systems.size());
        sourceCoverage.put(cfg.getSystemRelationTable(), systemRelations.size());
        sourceCoverage.put(cfg.getApiFlowTable(), apiFlows.size());
        sourceCoverage.put(cfg.getDbObjectTable(), dbObjects.size());
        sourceCoverage.put(cfg.getDbColumnTable(), dbColumns.size());
        sourceCoverage.put(cfg.getDbJoinPathTable(), dbJoinPaths.size());
        sourceCoverage.put(cfg.getStatusDictionaryTable(), statuses.size());
        sourceCoverage.put(cfg.getIdLineageTable(), lineages.size());
        sourceCoverage.put("ce_mcp_executor_template", executorTemplates.size());
        sourceCoverage.put(cfg.getQueryTemplateTable(), queryTemplates.size());
        sourceCoverage.put(cfg.getQueryParamRuleTable(), queryParamRules.size());
        sourceCoverage.put(cfg.getPlaybookStepTable(), playbookSteps.size());
        sourceCoverage.put(cfg.getPlaybookTransitionTable(), playbookTransitions.size());
        sourceCoverage.put(cfg.getOutcomeRuleTable(), outcomeRules.size());
        sourceCoverage.put(mcpConfig.getDb().getSqlGuardrailTable(), sqlGuardrails.size());
        sourceCoverage.put("ce_mcp_tool", mcpTools.size());
        sourceCoverage.put("ce_mcp_planner", mcpPlanners.size());
        capsule.put("sourceCoverage", sourceCoverage);

        Map<String, Object> semanticGraph = new LinkedHashMap<>();
        semanticGraph.put("cases", sampleCodes(caseTypes, "case_code", "case_name", 8));
        semanticGraph.put("playbooks", sampleCodes(playbooks, "playbook_code", "playbook_name", 10));
        semanticGraph.put("entities", sampleCodes(domainEntities, "entity_code", "entity_name", 12));
        semanticGraph.put("systems", sampleCodes(systems, "system_code", "system_name", 10));
        semanticGraph.put("apiFlows", sampleCodes(apiFlows, "api_code", "api_name", 12));
        capsule.put("semanticGraph", semanticGraph);

        Map<String, Object> sqlGraph = new LinkedHashMap<>();
        sqlGraph.put("objects", sampleCodes(dbObjects, "object_name", "description", 16));
        sqlGraph.put("joinPaths", sampleJoinPaths(dbJoinPaths, 12));
        sqlGraph.put("lineage", sampleLineage(lineages, 12));
        sqlGraph.put("statusDictionary", sampleStatus(statuses, 16));
        sqlGraph.put("queryTemplates", sampleQueryTemplates(queryTemplates, 12));
        sqlGraph.put("queryParamRules", sampleQueryParamRules(queryParamRules, 20));
        sqlGraph.put("columnsByObject", sampleColumnsByObject(dbColumns, 10, 12));
        capsule.put("sqlGraph", sqlGraph);

        Map<String, Object> executionGraph = new LinkedHashMap<>();
        String selectedPlaybookCode = asText(selectedPlaybook == null ? null : selectedPlaybook.get("playbookCode"));
        executionGraph.put("executorTemplates", sampleCodes(executorTemplates, "executor_code", "description", 12));
        executionGraph.put("steps", samplePlaybookSteps(playbookSteps, selectedPlaybookCode, 20));
        executionGraph.put("transitions", samplePlaybookTransitions(playbookTransitions, selectedPlaybookCode, 20));
        executionGraph.put("outcomes", sampleOutcomeRules(outcomeRules, selectedPlaybookCode, 16));
        executionGraph.put("sqlGuardrails", sampleGuardrails(sqlGuardrails, 20));
        capsule.put("executionGraph", executionGraph);

        Map<String, Object> plannerRuntime = new LinkedHashMap<>();
        plannerRuntime.put("mcpTools", sampleCodes(mcpTools, "tool_code", "description", 16));
        plannerRuntime.put("mcpPlanners", samplePlannerScopes(mcpPlanners, 12));
        plannerRuntime.put("hints", collectHints(24, caseTypes, playbooks, domainEntities, systems, dbObjects, dbColumns, queryTemplates, outcomeRules));
        capsule.put("plannerRuntime", plannerRuntime);
        return capsule;
    }

    private List<Map<String, Object>> sampleCodes(List<Map<String, Object>> rows, String codeKey, String labelKey, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "code", row.get(codeKey));
            putIfPresent(item, "label", row.get(labelKey));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<String> sampleJoinPaths(List<Map<String, Object>> rows, int limit) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String join = asText(row.get("join_sql_fragment"));
            if (join.isBlank()) {
                join = asText(row.get("left_object_name")) + " -> " + asText(row.get("right_object_name"));
            }
            if (!join.isBlank()) {
                out.add(join);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<String> sampleLineage(List<Map<String, Object>> rows, int limit) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String value = asText(row.get("source_object_name")) + "." + asText(row.get("source_column_name"))
                    + " -> " + asText(row.get("target_object_name")) + "." + asText(row.get("target_column_name"));
            out.add(value);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<String> sampleStatus(List<Map<String, Object>> rows, int limit) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String value = asText(row.get("dictionary_name")) + "." + asText(row.get("field_name"))
                    + "=" + asText(row.get("code_value")) + "(" + asText(row.get("code_label")) + ")";
            if (!value.isBlank()) {
                out.add(value);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> sampleQueryTemplates(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "queryCode", row.get("query_code"));
            putIfPresent(item, "playbookCode", row.get("playbook_code"));
            putIfPresent(item, "purpose", row.get("purpose"));
            putIfPresent(item, "safetyClass", row.get("safety_class"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> sampleQueryParamRules(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "queryCode", row.get("query_code"));
            putIfPresent(item, "param", row.get("param_name"));
            putIfPresent(item, "sourceType", row.get("source_type"));
            putIfPresent(item, "sourceKey", row.get("source_key"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private Map<String, List<String>> sampleColumnsByObject(List<Map<String, Object>> rows, int maxObjects, int maxColumnsPerObject) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String objectName = asText(row.get("object_name"));
            String columnName = asText(row.get("column_name"));
            if (objectName.isBlank() || columnName.isBlank()) {
                continue;
            }
            List<String> columns = out.computeIfAbsent(objectName, ignored -> new ArrayList<>());
            if (columns.size() < maxColumnsPerObject && !columns.contains(columnName)) {
                columns.add(columnName);
            }
            if (out.size() >= maxObjects) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> samplePlaybookSteps(List<Map<String, Object>> rows, String selectedPlaybookCode, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!selectedPlaybookCode.isBlank() && !selectedPlaybookCode.equalsIgnoreCase(asText(row.get("playbook_code")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "playbookCode", row.get("playbook_code"));
            putIfPresent(item, "stepCode", row.get("step_code"));
            putIfPresent(item, "executorCode", row.get("executor_code"));
            putIfPresent(item, "templateCode", row.get("template_code"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> samplePlaybookTransitions(List<Map<String, Object>> rows, String selectedPlaybookCode, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!selectedPlaybookCode.isBlank() && !selectedPlaybookCode.equalsIgnoreCase(asText(row.get("playbook_code")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "playbookCode", row.get("playbook_code"));
            putIfPresent(item, "from", row.get("from_step_code"));
            putIfPresent(item, "to", row.get("to_step_code"));
            putIfPresent(item, "outcome", row.get("outcome_code"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> sampleOutcomeRules(List<Map<String, Object>> rows, String selectedPlaybookCode, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!selectedPlaybookCode.isBlank() && !selectedPlaybookCode.equalsIgnoreCase(asText(row.get("playbook_code")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "playbookCode", row.get("playbook_code"));
            putIfPresent(item, "outcomeCode", row.get("outcome_code"));
            putIfPresent(item, "severity", row.get("severity"));
            putIfPresent(item, "recommendedNextAction", row.get("recommended_next_action"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<String> sampleGuardrails(List<Map<String, Object>> rows, int limit) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String value = asText(row.get("rule_type")) + ":" + asText(row.get("match_value"));
            if (!value.isBlank()) {
                out.add(value);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> samplePlannerScopes(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "intentCode", row.get("intent_code"));
            putIfPresent(item, "stateCode", row.get("state_code"));
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    @SafeVarargs
    private final List<String> collectHints(List<Map<String, Object>>... datasets) {
        return collectHints(24, datasets);
    }

    @SafeVarargs
    private final List<String> collectHints(int limit, List<Map<String, Object>>... datasets) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (List<Map<String, Object>> rows : datasets) {
            for (Map<String, Object> row : rows) {
                for (String key : List.of("llm_hint", "description", "purpose", "business_meaning", "explanation_template")) {
                    String hint = asText(row.get(key)).trim();
                    if (!hint.isBlank()) {
                        hints.add(hint);
                    }
                    if (hints.size() >= limit) {
                        return hints.stream().toList();
                    }
                }
            }
        }
        return hints.stream().toList();
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
