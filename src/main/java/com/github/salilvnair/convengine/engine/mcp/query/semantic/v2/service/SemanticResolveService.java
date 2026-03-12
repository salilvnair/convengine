package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedJoinPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSelectItem;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSemanticPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedTimeRange;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticAmbiguity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticAmbiguityOption;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticUnresolvedItem;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SemanticResolveService {

    private static final String TOOL_CODE = "db.semantic.resolve";
    private static final String VERSION = "v2";

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticModelRegistry modelRegistry;
    private final SemanticResolveMappingValidator mappingValidator;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticResolveResponse resolve(SemanticResolveRequest request, EngineSession session) {
        UUID conversationId = session == null ? null : session.getConversationId();
        CanonicalIntent canonicalIntent = request == null ? null : request.canonicalIntent();
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("tool", TOOL_CODE);
        inputPayload.put("version", VERSION);
        inputPayload.put("semantic_v2_stage", "resolve");
        inputPayload.put("semantic_v2_event", "input");
        inputPayload.put("canonicalIntent", canonicalIntent);
        inputPayload.put("context", request == null ? Map.of() : safeMap(request.context()));
        audit("SEMANTIC_RESOLVE_INPUT", conversationId, inputPayload);
        verbose(session, "SEMANTIC_RESOLVE_INPUT", false, inputPayload);

        List<SemanticUnresolvedItem> unresolved = new ArrayList<>(mappingValidator.validateCanonicalIntent(canonicalIntent));
        SemanticModel model = modelRegistry.getModel();

        String entityCode = canonicalIntent == null || canonicalIntent.entity() == null
                ? ""
                : canonicalIntent.entity().trim();
        SemanticEntity entity = findEntity(model, entityCode);
        String entityName = entity == null ? entityCode.toUpperCase(Locale.ROOT) : resolveEntityName(model, entity);

        String queryClass = canonicalIntent == null || canonicalIntent.queryClass() == null || canonicalIntent.queryClass().isBlank()
                ? "LIST_REQUESTS"
                : canonicalIntent.queryClass().trim().toUpperCase(Locale.ROOT);

        QueryClassDef queryClassDef = loadQueryClassDef(queryClass);
        String baseTable = resolveBaseTable(entity, queryClassDef);
        if (baseTable == null || baseTable.isBlank()) {
            unresolved.add(new SemanticUnresolvedItem("BASE_TABLE", "entity", "Could not resolve base table for entity " + entityName));
        }

        List<MappingRow> mappingRows = loadMappingRows();

        List<ResolvedSelectItem> select = resolveSelect(entityName, queryClass, queryClassDef, unresolved, mappingRows);
        List<ResolvedFilter> filters = resolveFilters(canonicalIntent, entityName, queryClass, unresolved, mappingRows);

        ResolvedTimeRange timeRange = resolveTimeRange(canonicalIntent, entityName, queryClass, unresolved,
                request == null ? null : request.context(), mappingRows);
        List<ResolvedSort> sort = resolveSort(canonicalIntent, entityName, queryClass, queryClassDef, unresolved, mappingRows);

        Set<String> requiredTables = new LinkedHashSet<>();
        collectTables(requiredTables, select.stream().map(ResolvedSelectItem::column).toList());
        collectTables(requiredTables, filters.stream().map(ResolvedFilter::column).toList());
        collectTables(requiredTables, sort.stream().map(ResolvedSort::column).toList());
        if (timeRange != null && timeRange.column() != null) {
            collectTables(requiredTables, List.of(timeRange.column()));
        }
        if (baseTable != null) {
            requiredTables.remove(baseTable);
        }

        List<ResolvedJoinPlan> joins = resolveJoins(baseTable, requiredTables, model, entityName, unresolved);

        Integer limit = normalizeLimit(canonicalIntent == null ? null : canonicalIntent.limit());

        ResolvedSemanticPlan plan = new ResolvedSemanticPlan(
                queryClass,
                entityName,
                baseTable,
                select,
                filters,
                joins,
                timeRange,
                sort,
                limit
        );

        unresolved.addAll(mappingValidator.validateResolvedPlan(plan));
        unresolved = dedupeUnresolved(unresolved);

        double confidence = resolveConfidence(unresolved, canonicalIntent, plan);
        boolean needsClarification = !unresolved.isEmpty() || confidence < clarificationThreshold();
        String clarificationQuestion = needsClarification ? buildClarificationQuestion(unresolved, entityName) : null;
        List<SemanticAmbiguity> ambiguities = needsClarification ? unresolvedToAmbiguities(unresolved) : List.of();

        if (needsClarification && session != null && clarificationQuestion != null && !clarificationQuestion.isBlank()) {
            session.setPendingClarificationQuestion(clarificationQuestion);
            session.setPendingClarificationReason(ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
            session.addClarificationHistory();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", TOOL_CODE);
        payload.put("version", VERSION);
        payload.put("semantic_v2_stage", "resolve");
        payload.put("semantic_v2_event", "output");
        payload.put("canonicalIntent", canonicalIntent);
        payload.put("resolvedPlan", plan);
        payload.put("unresolved", unresolved);
        payload.put("unresolvedCount", unresolved.size());
        payload.put("confidence", confidence);
        payload.put("needsClarification", needsClarification);
        audit("SEMANTIC_RESOLVE_OUTPUT", conversationId, payload);
        verbose(session, "SEMANTIC_RESOLVE_OUTPUT", false, payload);

        SemanticToolMeta meta = new SemanticToolMeta(
                TOOL_CODE,
                VERSION,
                confidence,
                needsClarification,
                clarificationQuestion,
                ambiguities
        );

        return new SemanticResolveResponse(meta, plan, unresolved);
    }

    private List<ResolvedSelectItem> resolveSelect(String entityName,
                                                   String queryClass,
                                                   QueryClassDef queryClassDef,
                                                   List<SemanticUnresolvedItem> unresolved,
                                                   List<MappingRow> mappingRows) {
        List<String> desiredFields = new ArrayList<>();
        if (queryClassDef != null && queryClassDef.defaultSelect() != null && !queryClassDef.defaultSelect().isEmpty()) {
            desiredFields.addAll(queryClassDef.defaultSelect());
        }

        List<ResolvedSelectItem> select = new ArrayList<>();
        for (String field : desiredFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            MappingResolution mapping = resolveMapping(entityName, field, queryClass, null, mappingRows);
            if (mapping == null || mapping.column() == null || mapping.column().isBlank()) {
                unresolved.add(new SemanticUnresolvedItem("SELECT", field, "No column mapping found for select field."));
                continue;
            }
            select.add(new ResolvedSelectItem(field, mapping.column()));
        }
        return select;
    }

    private List<ResolvedFilter> resolveFilters(CanonicalIntent canonicalIntent,
                                                String entityName,
                                                String queryClass,
                                                List<SemanticUnresolvedItem> unresolved,
                                                List<MappingRow> mappingRows) {
        if (canonicalIntent == null || canonicalIntent.filters() == null || canonicalIntent.filters().isEmpty()) {
            return List.of();
        }

        List<ResolvedFilter> filters = new ArrayList<>();
        for (SemanticFilter filter : canonicalIntent.filters()) {
            if (filter == null || filter.field() == null || filter.field().isBlank()) {
                continue;
            }
            MappingResolution mapping = resolveMapping(entityName, filter.field(), queryClass, filter.value(), mappingRows);
            if (mapping != null) {
                filters.add(new ResolvedFilter(filter.field(), mapping.column(), mapping.op(), mapping.value()));
                continue;
            }
            unresolved.add(new SemanticUnresolvedItem("FILTER_FIELD", filter.field(), "No deterministic mapping found for filter field."));
        }
        return filters;
    }

    private List<ResolvedSort> resolveSort(CanonicalIntent canonicalIntent,
                                           String entityName,
                                           String queryClass,
                                           QueryClassDef queryClassDef,
                                           List<SemanticUnresolvedItem> unresolved,
                                           List<MappingRow> mappingRows) {
        List<SemanticSort> requestedSorts = canonicalIntent == null || canonicalIntent.sort() == null
                ? List.of()
                : canonicalIntent.sort();

        if (requestedSorts.isEmpty() && queryClassDef != null && queryClassDef.defaultSort() != null) {
            requestedSorts = parseDefaultSort(queryClassDef.defaultSort());
        }

        List<ResolvedSort> sort = new ArrayList<>();
        for (SemanticSort semanticSort : requestedSorts) {
            if (semanticSort == null || semanticSort.field() == null || semanticSort.field().isBlank()) {
                continue;
            }

            MappingResolution mapping = resolveMapping(entityName, semanticSort.field(), queryClass, null, mappingRows);
            if (mapping != null) {
                sort.add(new ResolvedSort(mapping.column(), normalizeDirection(semanticSort.direction())));
                continue;
            }
            unresolved.add(new SemanticUnresolvedItem("SORT_FIELD", semanticSort.field(), "No deterministic mapping found for sort field."));
        }
        return sort;
    }

    private ResolvedTimeRange resolveTimeRange(CanonicalIntent canonicalIntent,
                                               String entityName,
                                               String queryClass,
                                               List<SemanticUnresolvedItem> unresolved,
                                               Map<String, Object> context,
                                               List<MappingRow> mappingRows) {
        if (canonicalIntent == null || canonicalIntent.timeRange() == null) {
            return null;
        }

        MappingResolution createdAtMapping = resolveMapping(entityName, "created_at", queryClass, null, mappingRows);
        String column = createdAtMapping == null ? null : createdAtMapping.column();
        if (column == null) {
            unresolved.add(new SemanticUnresolvedItem("TIME_RANGE", "timeRange", "No deterministic time column mapping found for created_at."));
            return null;
        }

        String timezone = canonicalIntent.timeRange().timezone();
        if (timezone == null || timezone.isBlank()) {
            timezone = defaultTimezone(context);
        }

        String kind = canonicalIntent.timeRange().kind() == null ? "RELATIVE" : canonicalIntent.timeRange().kind().trim().toUpperCase(Locale.ROOT);
        String value = canonicalIntent.timeRange().value() == null ? "" : canonicalIntent.timeRange().value().trim().toUpperCase(Locale.ROOT);

        if ("ABSOLUTE".equals(kind)) {
            return new ResolvedTimeRange(column, canonicalIntent.timeRange().from(), canonicalIntent.timeRange().to(), timezone);
        }

        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        LocalDate from;
        LocalDate to;
        switch (value) {
            case "TODAY" -> {
                from = today;
                to = today;
            }
            case "YESTERDAY" -> {
                from = today.minusDays(1);
                to = today.minusDays(1);
            }
            case "LAST_WEEK" -> {
                from = today.minusDays(7);
                to = today;
            }
            case "LAST_MONTH" -> {
                from = today.minusDays(30);
                to = today;
            }
            default -> {
                unresolved.add(new SemanticUnresolvedItem("TIME_RANGE", value, "Unsupported relative time range value."));
                return null;
            }
        }
        return new ResolvedTimeRange(column, from.toString(), to.toString(), timezone);
    }

    private List<ResolvedJoinPlan> resolveJoins(String baseTable,
                                                Set<String> requiredTables,
                                                SemanticModel model,
                                                String entityName,
                                                List<SemanticUnresolvedItem> unresolved) {
        if (baseTable == null || baseTable.isBlank() || requiredTables == null || requiredTables.isEmpty()) {
            return List.of();
        }

        List<ResolvedJoinPlan> joins = new ArrayList<>();
        List<JoinPathRow> joinPathRows = loadJoinPathRows(entityName);

        for (String requiredTable : requiredTables) {
            if (requiredTable == null || requiredTable.isBlank()) {
                continue;
            }

            ResolvedJoinPlan fromDb = resolveJoinFromDb(baseTable, requiredTable, joinPathRows);
            if (fromDb != null) {
                joins.add(fromDb);
                continue;
            }

            ResolvedJoinPlan fromModel = resolveJoinFromRelationships(baseTable, requiredTable, model == null ? List.of() : model.relationships());
            if (fromModel != null) {
                joins.add(fromModel);
                continue;
            }

            unresolved.add(new SemanticUnresolvedItem("JOIN_PATH", requiredTable,
                    "Could not resolve deterministic join path from " + baseTable + " to " + requiredTable));
        }

        return joins;
    }

    private ResolvedJoinPlan resolveJoinFromDb(String baseTable,
                                               String requiredTable,
                                               List<JoinPathRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (JoinPathRow row : rows) {
            if (row == null || row.joinExpression() == null) {
                continue;
            }
            String sql = row.joinExpression().toLowerCase(Locale.ROOT);
            if (!sql.contains(baseTable.toLowerCase(Locale.ROOT)) || !sql.contains(requiredTable.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return new ResolvedJoinPlan(baseTable, requiredTable, "LEFT", row.joinExpression());
        }
        return null;
    }

    private ResolvedJoinPlan resolveJoinFromRelationships(String baseTable,
                                                          String requiredTable,
                                                          List<SemanticRelationship> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return null;
        }
        for (SemanticRelationship relationship : relationships) {
            if (relationship == null || relationship.from() == null || relationship.to() == null) {
                continue;
            }
            String fromTable = relationship.from().table();
            String toTable = relationship.to().table();
            if (equalsIgnoreCase(fromTable, baseTable) && equalsIgnoreCase(toTable, requiredTable)) {
                return new ResolvedJoinPlan(baseTable, requiredTable, "LEFT",
                        fromTable + "." + relationship.from().column() + " = " + toTable + "." + relationship.to().column());
            }
            if (equalsIgnoreCase(toTable, baseTable) && equalsIgnoreCase(fromTable, requiredTable)) {
                return new ResolvedJoinPlan(baseTable, requiredTable, "LEFT",
                        toTable + "." + relationship.to().column() + " = " + fromTable + "." + relationship.from().column());
            }
        }
        return null;
    }

    private MappingResolution resolveMapping(String entityName,
                                             String field,
                                             String queryClass,
                                             Object value,
                                             List<MappingRow> rows) {
        if (rows == null || rows.isEmpty() || field == null || field.isBlank()) {
            return null;
        }

        String normalizedField = field.trim().toLowerCase(Locale.ROOT);
        List<MappingRow> candidates = new ArrayList<>();
        for (MappingRow row : rows) {
            if (row == null || row.mappedTable() == null || row.mappedTable().isBlank()
                    || row.mappedColumn() == null || row.mappedColumn().isBlank()) {
                continue;
            }
            if (row.queryClassKey() != null && !row.queryClassKey().isBlank()
                    && queryClass != null && !queryClass.equalsIgnoreCase(row.queryClassKey())) {
                continue;
            }
            if (entityName != null && !entityName.isBlank()) {
                if (row.entityKey() == null || row.entityKey().isBlank()
                        || !row.entityKey().equalsIgnoreCase(entityName)) {
                    continue;
                }
            }
            if (!fieldMatches(row.fieldKey(), normalizedField)) {
                continue;
            }
            candidates.add(row);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingInt(MappingRow::priority));
        MappingRow selected = candidates.getFirst();

        Object mappedValue = value;
        if (selected.valueMapJson() != null && !selected.valueMapJson().isBlank() && value != null) {
            mappedValue = applyValueMapping(selected.valueMapJson(), value);
        }

        return new MappingResolution(selected.mappedTable() + "." + selected.mappedColumn(),
                normalizeOp(selected.operatorType()), mappedValue);
    }

    private boolean fieldMatches(String mappedField, String inputFieldLower) {
        if (mappedField == null || mappedField.isBlank() || inputFieldLower == null || inputFieldLower.isBlank()) {
            return false;
        }
        String a = normalizeFieldKey(mappedField);
        String b = normalizeFieldKey(inputFieldLower);
        return a.equalsIgnoreCase(b);
    }

    private String normalizeFieldKey(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String out = field.trim();
        out = out.replaceAll("[^A-Za-z0-9]", "");
        return out.toLowerCase(Locale.ROOT);
    }

    private Object applyValueMapping(String valueMappingJson, Object value) {
        try {
            Map<String, List<String>> mapping = mapper.readValue(valueMappingJson, new TypeReference<>() {});
            if (mapping == null || mapping.isEmpty()) {
                return value;
            }
            String canonical = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            List<String> mapped = mapping.get(canonical);
            if (mapped == null || mapped.isEmpty()) {
                return value;
            }
            if (mapped.size() == 1) {
                return mapped.getFirst();
            }
            return mapped;
        } catch (Exception ignored) {
            return value;
        }
    }

    private String resolveBaseTable(SemanticEntity entity, QueryClassDef queryClassDef) {
        if (queryClassDef != null && queryClassDef.baseTable() != null && !queryClassDef.baseTable().isBlank()) {
            return queryClassDef.baseTable();
        }
        if (entity != null && entity.tables() != null && entity.tables().primary() != null) {
            return entity.tables().primary();
        }
        return null;
    }

    private SemanticEntity findEntity(SemanticModel model, String requestedEntity) {
        if (model == null || model.entities() == null || model.entities().isEmpty()) {
            return null;
        }
        if (requestedEntity == null || requestedEntity.isBlank()) {
            return model.entities().values().stream().findFirst().orElse(null);
        }
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            String key = entry.getKey();
            if (equalsIgnoreCase(key, requestedEntity)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            SemanticEntity entity = entry.getValue();
            if (entity == null || entity.synonyms() == null) {
                continue;
            }
            for (String synonym : entity.synonyms()) {
                if (equalsIgnoreCase(synonym, requestedEntity)) {
                    return entity;
                }
            }
        }
        return null;
    }

    private String resolveEntityName(SemanticModel model, SemanticEntity entity) {
        if (model == null || model.entities() == null || entity == null) {
            return "REQUEST";
        }
        for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
            if (entry.getValue() == entity) {
                return entry.getKey();
            }
        }
        return "REQUEST";
    }

    private List<MappingRow> loadMappingRows() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        String sql = """
                SELECT concept_key, entity_key, field_key, mapped_table, mapped_column, operator_type, value_map_json, query_class_key, priority
                FROM ce_semantic_mapping
                WHERE enabled = true
                ORDER BY COALESCE(priority, 999999), entity_key, field_key
                """;
        try {
            return jdbc.queryForList(sql, Map.of()).stream()
                    .map(row -> new MappingRow(
                            asText(row.get("concept_key")),
                            asText(row.get("entity_key")),
                            asText(row.get("field_key")),
                            asText(row.get("mapped_table")),
                            asText(row.get("mapped_column")),
                            asText(row.get("operator_type")),
                            asText(row.get("value_map_json")),
                            asText(row.get("query_class_key")),
                            asInt(row.get("priority"), 999999)
                    ))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<JoinPathRow> loadJoinPathRows(String entityName) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return List.of();
        }
        String sql = """
                SELECT left_entity_key, right_entity_key, join_expression, join_priority
                FROM ce_semantic_join_path
                WHERE enabled = true
                  AND (UPPER(left_entity_key) = UPPER(:entity) OR :entity = '')
                ORDER BY COALESCE(join_priority, 999999)
                """;
        try {
            return jdbc.queryForList(sql, Map.of("entity", entityName == null ? "" : entityName)).stream()
                    .map(row -> new JoinPathRow(
                            asText(row.get("left_entity_key")),
                            asText(row.get("right_entity_key")),
                            asText(row.get("join_expression")),
                            asInt(row.get("join_priority"), 999999)
                    ))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private QueryClassDef loadQueryClassDef(String queryClass) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || queryClass == null || queryClass.isBlank()) {
            return null;
        }
        String sql = """
                SELECT query_class_key, base_table_name, default_select_fields_json, default_sort_fields_json
                FROM ce_semantic_query_class
                WHERE enabled = true
                  AND UPPER(query_class_key) = UPPER(:queryClass)
                ORDER BY COALESCE(priority, 999999)
                LIMIT 1
                """;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("queryClass", queryClass));
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.getFirst();
            return new QueryClassDef(
                    asText(row.get("query_class_key")),
                    asText(row.get("base_table_name")),
                    parseStringList(row.get("default_select_fields_json")),
                    parseStringList(row.get("default_sort_fields_json"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        out.add(String.valueOf(item).trim());
                    }
                }
                return out;
            }
            String raw = String.valueOf(value).trim();
            if (raw.isBlank()) {
                return List.of();
            }
            if (raw.startsWith("[") && raw.endsWith("]")) {
                return mapper.readValue(raw, new TypeReference<List<String>>() {});
            }
            return List.of(raw);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<SemanticUnresolvedItem> dedupeUnresolved(List<SemanticUnresolvedItem> unresolved) {
        if (unresolved == null || unresolved.isEmpty()) {
            return List.of();
        }
        Map<String, SemanticUnresolvedItem> deduped = new LinkedHashMap<>();
        for (SemanticUnresolvedItem item : unresolved) {
            if (item == null) {
                continue;
            }
            String key = (item.type() + "|" + item.field() + "|" + item.reason()).toUpperCase(Locale.ROOT);
            deduped.putIfAbsent(key, item);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<SemanticAmbiguity> unresolvedToAmbiguities(List<SemanticUnresolvedItem> unresolved) {
        if (unresolved == null || unresolved.isEmpty()) {
            return List.of();
        }
        List<SemanticAmbiguity> ambiguities = new ArrayList<>();
        for (SemanticUnresolvedItem item : unresolved) {
            if (item == null) {
                continue;
            }
            String code = (item.type() == null ? "UNRESOLVED" : item.type())
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_");
            String field = item.field() == null ? "unknown" : item.field();
            ambiguities.add(new SemanticAmbiguity(
                    "FIELD",
                    code,
                    item.reason() == null ? "Unresolved mapping" : item.reason(),
                    true,
                    List.of(new SemanticAmbiguityOption(field, field, 1.0d))
            ));
        }
        return ambiguities;
    }

    private double resolveConfidence(List<SemanticUnresolvedItem> unresolved,
                                     CanonicalIntent canonicalIntent,
                                     ResolvedSemanticPlan plan) {
        int expected = 1;
        if (canonicalIntent != null) {
            expected += canonicalIntent.filters() == null ? 0 : canonicalIntent.filters().size();
            expected += canonicalIntent.sort() == null ? 0 : canonicalIntent.sort().size();
            expected += canonicalIntent.timeRange() == null ? 0 : 1;
        }
        expected += plan == null || plan.joins() == null ? 0 : plan.joins().size();
        expected = Math.max(expected, 1);

        int unresolvedCount = unresolved == null ? 0 : unresolved.size();
        double penalty = (double) unresolvedCount / (double) expected;
        double confidence = 1.0d - Math.min(0.85d, penalty * 0.85d);
        return clamp01(confidence);
    }

    private String buildClarificationQuestion(List<SemanticUnresolvedItem> unresolved, String entityName) {
        if (unresolved == null || unresolved.isEmpty()) {
            return null;
        }
        SemanticUnresolvedItem top = unresolved.getFirst();
        String field = top.field() == null ? "unknown" : top.field();
        return "I could not map '" + field + "' deterministically for entity "
                + (entityName == null || entityName.isBlank() ? "REQUEST" : entityName)
                + ". Please clarify the exact business field/value.";
    }

    private Integer normalizeLimit(Integer raw) {
        int fallback = mcpConfig.getDb() == null || mcpConfig.getDb().getSemantic() == null
                ? 100
                : mcpConfig.getDb().getSemantic().getDefaultLimit();
        int max = mcpConfig.getDb() == null || mcpConfig.getDb().getSemantic() == null
                ? 500
                : mcpConfig.getDb().getSemantic().getMaxLimit();
        int resolved = raw == null || raw <= 0 ? fallback : raw;
        return Math.min(Math.max(1, resolved), Math.max(1, max));
    }

    private String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            return "EQ";
        }
        String normalized = op.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "=", "EQ", "EQUALS" -> "EQ";
            case "IN" -> "IN";
            case "NOT_IN" -> "NOT_IN";
            case "LIKE" -> "LIKE";
            case "ILIKE" -> "ILIKE";
            case "GT", ">" -> "GT";
            case "GTE", ">=" -> "GTE";
            case "LT", "<" -> "LT";
            case "LTE", "<=" -> "LTE";
            case "BETWEEN" -> "BETWEEN";
            default -> "EQ";
        };
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "DESC";
        }
        return "ASC".equalsIgnoreCase(direction.trim()) ? "ASC" : "DESC";
    }

    private List<SemanticSort> parseDefaultSort(List<String> rawSort) {
        if (rawSort == null || rawSort.isEmpty()) {
            return List.of();
        }
        List<SemanticSort> out = new ArrayList<>();
        for (String item : rawSort) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String normalized = item.trim();
            String[] parts = normalized.split("\\s+");
            String field = parts[0];
            String direction = parts.length > 1 ? parts[1] : "DESC";
            out.add(new SemanticSort(field, normalizeDirection(direction)));
        }
        return out;
    }

    private String defaultTimezone(Map<String, Object> context) {
        if (context != null) {
            Object timezone = context.get("timezone");
            if (timezone != null && !String.valueOf(timezone).isBlank()) {
                return String.valueOf(timezone).trim();
            }
        }
        ConvEngineMcpConfig.Db.Semantic semantic = mcpConfig.getDb() == null ? null : mcpConfig.getDb().getSemantic();
        if (semantic == null || semantic.getTimezone() == null || semantic.getTimezone().isBlank()) {
            return "UTC";
        }
        return semantic.getTimezone().trim();
    }

    private double clarificationThreshold() {
        ConvEngineMcpConfig.Db.Semantic.Clarification clarification = mcpConfig.getDb() == null || mcpConfig.getDb().getSemantic() == null
                ? null
                : mcpConfig.getDb().getSemantic().getClarification();
        if (clarification == null) {
            return 0.80d;
        }
        return clamp01(clarification.getConfidenceThreshold());
    }

    private void collectTables(Set<String> out, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            String normalized = column.trim();
            int dot = normalized.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            out.add(normalized.substring(0, dot));
        }
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        return Math.min(value, 1.0d);
    }

    private void audit(String stage, UUID conversationId, Map<String, Object> payload) {
        if (conversationId == null) {
            return;
        }
        auditService.audit(stage, conversationId, payload == null ? Map.of() : payload);
    }

    private Map<String, Object> safeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(map);
    }

    private void verbose(EngineSession session, String determinant, boolean error, Map<String, Object> payload) {
        if (session == null || verbosePublisher == null) {
            return;
        }
        verbosePublisher.publish(session,
                "SemanticResolveService",
                determinant,
                null,
                TOOL_CODE,
                error,
                payload == null ? Map.of() : payload);
    }

    private record QueryClassDef(
            String queryClass,
            String baseTable,
            List<String> defaultSelect,
            List<String> defaultSort
    ) {
    }

    private record MappingRow(
            String conceptKey,
            String entityKey,
            String fieldKey,
            String mappedTable,
            String mappedColumn,
            String operatorType,
            String valueMapJson,
            String queryClassKey,
            int priority
    ) {
    }

    private record JoinPathRow(
            String leftEntityKey,
            String rightEntityKey,
            String joinExpression,
            int priority
    ) {
    }

    private record MappingResolution(
            String column,
            String op,
            Object value
    ) {
    }
}
