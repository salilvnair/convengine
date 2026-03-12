package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentExists;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentFieldRemap;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentRule;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultAstNormalizer implements AstNormalizer {

    private final ObjectProvider<List<AstNormalizeInterceptor>> interceptorsProvider;

    @Override
    public SemanticQueryAstV1 normalize(SemanticQueryAstV1 ast, SemanticModel model, String selectedEntity, EngineSession session) {
        List<AstNormalizeInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (AstNormalizeInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeNormalize(ast, model, selectedEntity, session);
            }
        }
        try {
            if (ast == null || model == null || model.entities() == null) {
                return ast;
            }
            String question = resolveQuestion(session);
            SemanticIntentRule rule = resolveIntentRule(question, model);
            String targetEntity = (ast.entity() == null || ast.entity().isBlank()) ? selectedEntity : ast.entity();
            if (rule != null && rule.forceEntity() != null && !rule.forceEntity().isBlank()
                    && model.entities().containsKey(rule.forceEntity())) {
                targetEntity = rule.forceEntity();
            }
            SemanticEntity entity = model.entities().get(targetEntity);
            if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
                return ast;
            }
            Map<String, String> aliasToField = buildFieldAliasMap(entity.fields());

            List<String> normalizedSelect = ast.select().stream()
                    .map(field -> normalizeField(field, aliasToField, entity.fields()))
                    .toList();
            List<AstFilter> normalizedFilters = ast.filters().stream()
                    .map(filter -> filter == null ? null : new AstFilter(
                            normalizeField(filter.field(), aliasToField, entity.fields()),
                            filter.op(),
                            filter.value()))
                    .toList();
            List<AstSort> normalizedSort = ast.sort().stream()
                    .map(sort -> sort == null ? null : new AstSort(
                            normalizeField(sort.field(), aliasToField, entity.fields()),
                            sort.direction(),
                            sort.nulls()))
                    .toList();
            List<String> normalizedGroupBy = ast.groupBy().stream()
                    .map(field -> normalizeField(field, aliasToField, entity.fields()))
                    .toList();
            List<AstProjection> normalizedProjections = ast.projections().stream()
                    .map(projection -> projection == null ? null : new AstProjection(
                            normalizeField(projection.field(), aliasToField, entity.fields()),
                            projection.alias()))
                    .toList();
            AstFilterGroup normalizedWhere = normalizeFilterGroup(ast.where(), aliasToField, entity.fields());
            AstFilterGroup normalizedHaving = normalizeFilterGroup(ast.having(), aliasToField, entity.fields());
            normalizedFilters = applyFieldRemaps(normalizedFilters, entity.fields(), model.valuePatterns());
            normalizedWhere = applyFieldRemaps(normalizedWhere, entity.fields(), model.valuePatterns());
            normalizedHaving = applyFieldRemaps(normalizedHaving, entity.fields(), model.valuePatterns());
            normalizedFilters = applyFieldRemaps(normalizedFilters, entity.fields(), rule);
            normalizedWhere = applyFieldRemaps(normalizedWhere, entity.fields(), rule);
            normalizedHaving = applyFieldRemaps(normalizedHaving, entity.fields(), rule);

            Set<String> metricNames = model.metrics() == null ? Set.of() : model.metrics().keySet();
            if (!ast.metrics().isEmpty() && normalizedGroupBy.isEmpty() && questionMentionsAnyMetric(question, ast.metrics())) {
                normalizedSelect = List.of();
                normalizedProjections = List.of();
            }
            boolean demoteToDetail = shouldDemoteAggregateToDetail(
                    normalizedSelect,
                    normalizedProjections,
                    normalizedGroupBy,
                    ast.metrics(),
                    metricNames
            );

            List<AstFilter> effectiveFilters = normalizedFilters;
            List<AstSort> effectiveSort = normalizedSort;
            List<String> effectiveGroupBy = normalizedGroupBy;
            List<String> effectiveMetrics = ast.metrics();
            AstFilterGroup effectiveWhere = normalizedWhere;
            AstFilterGroup effectiveHaving = normalizedHaving;

            if (demoteToDetail) {
                effectiveMetrics = List.of();
                effectiveGroupBy = List.of();
                effectiveSort = dropMetricSorts(normalizedSort, metricNames);
                effectiveFilters = dropMetricFilters(normalizedFilters, metricNames);
                effectiveWhere = dropMetricFilters(normalizedWhere, metricNames);
                effectiveHaving = new AstFilterGroup("AND", List.of(), List.of());
            }

            SemanticQueryAstV1 normalized = new SemanticQueryAstV1(
                    ast.astVersion(),
                    targetEntity,
                    normalizedSelect,
                    normalizedProjections,
                    effectiveFilters,
                    effectiveWhere,
                    ast.timeRange(),
                    ast.existsBlocks(),
                    ast.subqueryFilters(),
                    effectiveGroupBy,
                    effectiveMetrics,
                    ast.windows(),
                    effectiveSort,
                    effectiveHaving,
                    ast.limit(),
                    ast.offset(),
                    ast.distinct(),
                    ast.joinHints()
            );
            normalized = applyIntentRule(normalized, model, entity, metricNames, rule);
            assertNormalizationDidNotDropCriticalIntent(ast, normalized, targetEntity);
            for (AstNormalizeInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    normalized = interceptor.afterNormalize(normalized, session);
                }
            }
            return normalized;
        } catch (Exception ex) {
            for (AstNormalizeInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(ast, session, ex);
                }
            }
            throw ex;
        }
    }

    private Map<String, String> buildFieldAliasMap(Map<String, SemanticField> fields) {
        Map<String, String> aliasToField = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticField> e : fields.entrySet()) {
            String fieldName = e.getKey();
            SemanticField field = e.getValue();
            putAlias(aliasToField, normalizeToken(fieldName), fieldName);
            putAlias(aliasToField, normalizeToken(camelToSnake(fieldName)), fieldName);
            if (field != null && field.column() != null) {
                String col = field.column();
                String colName = col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col;
                putAlias(aliasToField, normalizeToken(colName), fieldName);
            }
            if (field != null && field.aliases() != null && !field.aliases().isEmpty()) {
                for (String alias : field.aliases()) {
                    putAlias(aliasToField, normalizeToken(alias), fieldName);
                }
            }
        }
        return aliasToField;
    }

    private void putAlias(Map<String, String> aliasToField, String alias, String fieldName) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        aliasToField.putIfAbsent(alias, fieldName);
    }

    private String normalizeField(String field, Map<String, String> aliasToField, Map<String, SemanticField> knownFields) {
        if (field == null || field.isBlank()) {
            return field;
        }
        if (knownFields.containsKey(field)) {
            return field;
        }
        String normalized = normalizeToken(field);
        String mapped = aliasToField.get(normalized);
        return mapped == null ? field : mapped;
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private String camelToSnake(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private boolean shouldDemoteAggregateToDetail(List<String> select,
                                                  List<AstProjection> projections,
                                                  List<String> groupBy,
                                                  List<String> metrics,
                                                  Set<String> metricNames) {
        if (metrics == null || metrics.isEmpty()) {
            return false;
        }
        if (projections == null || projections.isEmpty()) {
            return false;
        }
        if (groupBy == null || groupBy.isEmpty()) {
            return true;
        }
        Set<String> groupSet = new LinkedHashSet<>(groupBy);
        boolean projectionOutsideGroup = projections.stream()
                .filter(p -> p != null && p.field() != null && !p.field().isBlank())
                .anyMatch(p -> !groupSet.contains(p.field()));
        if (projectionOutsideGroup) {
            return true;
        }
        // If select carries metric names mixed with regular fields, prefer detail mode.
        return select != null && select.stream().anyMatch(metricNames::contains);
    }

    private List<AstSort> dropMetricSorts(List<AstSort> sorts, Set<String> metricNames) {
        if (sorts == null || sorts.isEmpty() || metricNames.isEmpty()) {
            return sorts == null ? List.of() : sorts;
        }
        return sorts.stream()
                .filter(s -> s == null || s.field() == null || !metricNames.contains(s.field()))
                .toList();
    }

    private List<AstFilter> dropMetricFilters(List<AstFilter> filters, Set<String> metricNames) {
        if (filters == null || filters.isEmpty() || metricNames.isEmpty()) {
            return filters == null ? List.of() : filters;
        }
        return filters.stream()
                .filter(f -> f == null || f.field() == null || !metricNames.contains(f.field()))
                .toList();
    }

    private AstFilterGroup normalizeFilterGroup(AstFilterGroup group,
                                                Map<String, String> aliasToField,
                                                Map<String, SemanticField> knownFields) {
        if (group == null) {
            return null;
        }
        List<AstFilter> conditions = group.conditions() == null ? List.of() : group.conditions().stream()
                .map(f -> f == null ? null : new AstFilter(
                        normalizeField(f.field(), aliasToField, knownFields),
                        f.op(),
                        f.value()))
                .toList();
        List<AstFilterGroup> groups = group.groups() == null ? List.of() : group.groups().stream()
                .map(g -> normalizeFilterGroup(g, aliasToField, knownFields))
                .toList();
        return new AstFilterGroup(group.op(), conditions, groups);
    }

    private List<AstFilter> applyFieldRemaps(List<AstFilter> filters,
                                             Map<String, SemanticField> fields,
                                             SemanticIntentRule rule) {
        return applyFieldRemaps(filters, fields, rule == null ? List.of() : rule.fieldRemaps());
    }

    private List<AstFilter> applyFieldRemaps(List<AstFilter> filters,
                                             Map<String, SemanticField> fields,
                                             List<SemanticIntentFieldRemap> remaps) {
        if (filters == null || filters.isEmpty() || fields == null || fields.isEmpty()
                || remaps == null || remaps.isEmpty()) {
            return filters == null ? List.of() : filters;
        }
        return filters.stream()
                .map(f -> applyFieldRemap(f, fields, remaps))
                .toList();
    }

    private AstFilterGroup applyFieldRemaps(AstFilterGroup group,
                                            Map<String, SemanticField> fields,
                                            SemanticIntentRule rule) {
        return applyFieldRemaps(group, fields, rule == null ? List.of() : rule.fieldRemaps());
    }

    private AstFilterGroup applyFieldRemaps(AstFilterGroup group,
                                            Map<String, SemanticField> fields,
                                            List<SemanticIntentFieldRemap> remaps) {
        if (group == null || fields == null || fields.isEmpty()
                || remaps == null || remaps.isEmpty()) {
            return group;
        }
        List<AstFilter> conditions = group.conditions() == null ? List.of() : group.conditions().stream()
                .map(f -> applyFieldRemap(f, fields, remaps))
                .toList();
        List<AstFilterGroup> groups = group.groups() == null ? List.of() : group.groups().stream()
                .map(g -> applyFieldRemaps(g, fields, remaps))
                .toList();
        return new AstFilterGroup(group.op(), conditions, groups);
    }

    private AstFilter applyFieldRemap(AstFilter filter,
                                      Map<String, SemanticField> fields,
                                      List<SemanticIntentFieldRemap> remaps) {
        if (filter == null || filter.field() == null || filter.field().isBlank()
                || fields == null || fields.isEmpty() || remaps == null || remaps.isEmpty()) {
            return filter;
        }
        String value = filter.value() == null ? null : String.valueOf(filter.value()).trim();
        String upper = value == null ? "" : value.toUpperCase(Locale.ROOT);
        for (SemanticIntentFieldRemap remap : remaps) {
            if (remap == null || remap.fromField() == null || remap.toField() == null) {
                continue;
            }
            if (!remap.fromField().equals(filter.field())) {
                continue;
            }
            if (!fields.containsKey(remap.toField())) {
                continue;
            }
            if (matchesValuePrefix(remap.valueStartsWith(), upper)) {
                return new AstFilter(remap.toField(), filter.op(), filter.value());
            }
        }
        return filter;
    }

    private boolean matchesValuePrefix(List<String> prefixes, String upperValue) {
        if (prefixes == null || prefixes.isEmpty()) {
            return true;
        }
        if (upperValue == null || upperValue.isBlank()) {
            return false;
        }
        for (String p : prefixes) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (upperValue.startsWith(p.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean questionMentionsAnyMetric(String question, List<String> metrics) {
        if (question == null || question.isBlank() || metrics == null || metrics.isEmpty()) {
            return false;
        }
        String normalizedQuestion = normalizeToken(question);
        if (normalizedQuestion.isBlank()) {
            return false;
        }
        for (String metric : metrics) {
            if (metric == null || metric.isBlank()) {
                continue;
            }
            String normalizedMetric = normalizeToken(metric);
            if (!normalizedMetric.isBlank() && normalizedQuestion.contains(normalizedMetric)) {
                return true;
            }
        }
        return false;
    }

    private AstFilterGroup dropMetricFilters(AstFilterGroup group, Set<String> metricNames) {
        if (group == null) {
            return null;
        }
        if (metricNames.isEmpty()) {
            return group;
        }
        List<AstFilter> conditions = group.conditions() == null ? List.of() : group.conditions().stream()
                .filter(f -> f == null || f.field() == null || !metricNames.contains(f.field()))
                .toList();
        List<AstFilterGroup> groups = group.groups() == null ? List.of() : group.groups().stream()
                .map(g -> dropMetricFilters(g, metricNames))
                .toList();
        return new AstFilterGroup(group.op(), conditions, groups);
    }

    private SemanticQueryAstV1 applyIntentRule(SemanticQueryAstV1 ast,
                                               SemanticModel model,
                                               SemanticEntity entity,
                                               Set<String> metricNames,
                                               SemanticIntentRule rule) {
        if (ast == null || entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return ast;
        }
        Map<String, SemanticField> fields = entity.fields();
        List<AstFilter> filters = pruneUnknownFilters(ast.filters(), fields.keySet());
        List<AstSort> sorts = pruneUnknownSorts(ast.sort(), fields.keySet());
        List<String> groupBy = pruneUnknownGroupBy(ast.groupBy(), fields.keySet());
        AstFilterGroup where = pruneUnknownFilters(ast.where(), fields.keySet());
        AstFilterGroup having = pruneUnknownFilters(ast.having(), fields.keySet());
        List<String> metrics = ast.metrics() == null ? List.of() : ast.metrics();

        List<String> select = pruneUnknownSelect(ast.select(), fields.keySet());
        List<AstProjection> projections = pruneUnknownProjections(ast.projections(), fields.keySet());

        boolean metricRequestedByAst = metrics != null && !metrics.isEmpty();
        boolean allowForceWithMetrics = rule != null && Boolean.TRUE.equals(rule.forceModeEvenWithMetrics());
        boolean allowDetailForce = !metricRequestedByAst || allowForceWithMetrics;

        if (allowDetailForce && rule != null && rule.forceSelect() != null && !rule.forceSelect().isEmpty()) {
            List<String> forcedSelect = rule.forceSelect().stream()
                    .filter(Objects::nonNull)
                    .filter(fields::containsKey)
                    .toList();
            if (!forcedSelect.isEmpty()) {
                select = forcedSelect;
                projections = forcedSelect.stream().map(f -> new AstProjection(f, null)).toList();
            }
        }

        boolean forceDetail = allowDetailForce && rule != null && "detail".equalsIgnoreCase(rule.forceMode());
        if (forceDetail) {
            metrics = List.of();
            groupBy = List.of();
            having = new AstFilterGroup("AND", List.of(), List.of());
            filters = dropMetricFilters(filters, metricNames);
            sorts = dropMetricSorts(sorts, metricNames);
            where = dropMetricFilters(where, metricNames);
        }

        if (rule != null && rule.enforceWhere() != null && !rule.enforceWhere().isEmpty()) {
            List<AstFilter> injected = rule.enforceWhere().stream()
                    .filter(Objects::nonNull)
                    .filter(f -> f.field() != null && fields.containsKey(f.field()))
                    .map(f -> new AstFilter(f.field(), f.op(), f.value()))
                    .toList();
            if (!injected.isEmpty()) {
                where = appendConditions(where, injected);
                filters = appendFlatFilters(filters, injected);
            }
        }
        List<AstExistsBlock> existsBlocks = ast.existsBlocks() == null ? List.of() : ast.existsBlocks();
        if (rule != null && rule.enforceExists() != null && !rule.enforceExists().isEmpty() && model != null
                && model.entities() != null && !model.entities().isEmpty()) {
            List<AstExistsBlock> injectedExists = rule.enforceExists().stream()
                    .map(intentExists -> toAstExistsBlock(intentExists, model))
                    .filter(Objects::nonNull)
                    .toList();
            existsBlocks = appendExistsBlocks(existsBlocks, injectedExists);
        }
        Set<String> nullConstrainedFields = collectNullConstrainedFields(where);
        existsBlocks = pruneExistsBlocksByNullCorrelation(existsBlocks, nullConstrainedFields);

        return new SemanticQueryAstV1(
                ast.astVersion(),
                ast.entity(),
                select,
                projections,
                filters,
                where,
                ast.timeRange(),
                existsBlocks,
                ast.subqueryFilters(),
                groupBy,
                metrics,
                ast.windows(),
                sorts,
                having,
                ast.limit(),
                ast.offset(),
                ast.distinct(),
                ast.joinHints()
        );
    }

    private AstExistsBlock toAstExistsBlock(SemanticIntentExists intentExists, SemanticModel model) {
        if (intentExists == null || intentExists.entity() == null || intentExists.entity().isBlank()
                || model == null || model.entities() == null) {
            return null;
        }
        SemanticEntity subEntity = model.entities().get(intentExists.entity());
        if (subEntity == null || subEntity.fields() == null || subEntity.fields().isEmpty()) {
            return null;
        }
        List<AstFilter> conditions = intentExists.where().stream()
                .filter(Objects::nonNull)
                .filter(f -> f.field() != null && subEntity.fields().containsKey(f.field()))
                .map(f -> new AstFilter(f.field(), f.op(), f.value()))
                .toList();
        if (conditions.isEmpty()) {
            return null;
        }
        return new AstExistsBlock(
                intentExists.entity(),
                new AstFilterGroup("AND", conditions, List.of()),
                Boolean.TRUE.equals(intentExists.notExists())
        );
    }

    private List<AstExistsBlock> appendExistsBlocks(List<AstExistsBlock> base, List<AstExistsBlock> extra) {
        List<AstExistsBlock> out = new java.util.ArrayList<>(base == null ? List.of() : base);
        for (AstExistsBlock block : extra) {
            boolean exists = out.stream().anyMatch(e -> sameExistsBlock(e, block));
            if (!exists) {
                out.add(block);
            }
        }
        return List.copyOf(out);
    }

    private boolean sameExistsBlock(AstExistsBlock a, AstExistsBlock b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.entity(), b.entity())
                && Objects.equals(Boolean.TRUE.equals(a.notExists()), Boolean.TRUE.equals(b.notExists()))
                && Objects.equals(a.where(), b.where());
    }

    private Set<String> collectNullConstrainedFields(AstFilterGroup group) {
        Set<String> out = new LinkedHashSet<>();
        collectNullConstrainedFields(group, out);
        return out;
    }

    private void collectNullConstrainedFields(AstFilterGroup group, Set<String> out) {
        if (group == null) {
            return;
        }
        if (group.conditions() != null) {
            for (AstFilter filter : group.conditions()) {
                if (filter == null || filter.field() == null || filter.field().isBlank()) {
                    continue;
                }
                if (filter.operatorEnum() == com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator.IS_NULL) {
                    out.add(filter.field());
                }
            }
        }
        if (group.groups() != null) {
            for (AstFilterGroup child : group.groups()) {
                collectNullConstrainedFields(child, out);
            }
        }
    }

    private List<AstExistsBlock> pruneExistsBlocksByNullCorrelation(List<AstExistsBlock> existsBlocks, Set<String> nullFields) {
        if (existsBlocks == null || existsBlocks.isEmpty() || nullFields == null || nullFields.isEmpty()) {
            return existsBlocks == null ? List.of() : existsBlocks;
        }
        return existsBlocks.stream()
                .filter(block -> !referencesAnyNullConstrainedOuterField(block, nullFields))
                .toList();
    }

    private boolean referencesAnyNullConstrainedOuterField(AstExistsBlock block, Set<String> nullFields) {
        if (block == null || block.where() == null || nullFields == null || nullFields.isEmpty()) {
            return false;
        }
        return referencesAnyNullConstrainedOuterField(block.where(), nullFields);
    }

    private boolean referencesAnyNullConstrainedOuterField(AstFilterGroup group, Set<String> nullFields) {
        if (group == null) {
            return false;
        }
        if (group.conditions() != null) {
            for (AstFilter filter : group.conditions()) {
                if (filter == null || !(filter.value() instanceof String valueRef)) {
                    continue;
                }
                if (!valueRef.startsWith("$") || valueRef.length() <= 1) {
                    continue;
                }
                String refField = valueRef.substring(1);
                if (nullFields.contains(refField)) {
                    return true;
                }
            }
        }
        if (group.groups() != null) {
            for (AstFilterGroup child : group.groups()) {
                if (referencesAnyNullConstrainedOuterField(child, nullFields)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<AstFilter> appendFlatFilters(List<AstFilter> base, List<AstFilter> extra) {
        List<AstFilter> out = new java.util.ArrayList<>(base == null ? List.of() : base);
        for (AstFilter filter : extra) {
            boolean exists = out.stream().anyMatch(f -> sameFilter(f, filter));
            if (!exists) {
                out.add(filter);
            }
        }
        return List.copyOf(out);
    }

    private AstFilterGroup appendConditions(AstFilterGroup group, List<AstFilter> injected) {
        AstFilterGroup base = group == null ? new AstFilterGroup("AND", List.of(), List.of()) : group;
        List<AstFilter> out = new java.util.ArrayList<>(base.conditions() == null ? List.of() : base.conditions());
        for (AstFilter filter : injected) {
            boolean exists = out.stream().anyMatch(f -> sameFilter(f, filter));
            if (!exists) {
                out.add(filter);
            }
        }
        String op = base.op() == null || base.op().isBlank() ? "AND" : base.op();
        return new AstFilterGroup(op, List.copyOf(out), base.groups() == null ? List.of() : base.groups());
    }

    private boolean sameFilter(AstFilter a, AstFilter b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.field(), b.field())
                && Objects.equals(a.op(), b.op())
                && Objects.equals(a.value(), b.value());
    }

    private List<AstFilter> pruneUnknownFilters(List<AstFilter> filters, Set<String> knownFields) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        return filters.stream()
                .filter(f -> f == null || f.field() == null || knownFields.contains(f.field()))
                .toList();
    }

    private List<AstSort> pruneUnknownSorts(List<AstSort> sorts, Set<String> knownFields) {
        if (sorts == null || sorts.isEmpty()) {
            return List.of();
        }
        return sorts.stream()
                .filter(s -> s == null || s.field() == null || knownFields.contains(s.field()))
                .toList();
    }

    private List<String> pruneUnknownGroupBy(List<String> groupBy, Set<String> knownFields) {
        if (groupBy == null || groupBy.isEmpty()) {
            return List.of();
        }
        return groupBy.stream()
                .filter(Objects::nonNull)
                .filter(knownFields::contains)
                .toList();
    }

    private List<String> pruneUnknownSelect(List<String> select, Set<String> knownFields) {
        if (select == null || select.isEmpty()) {
            return List.of();
        }
        return select.stream()
                .filter(Objects::nonNull)
                .filter(knownFields::contains)
                .toList();
    }

    private List<AstProjection> pruneUnknownProjections(List<AstProjection> projections, Set<String> knownFields) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }
        return projections.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.field() != null && knownFields.contains(p.field()))
                .toList();
    }

    private AstFilterGroup pruneUnknownFilters(AstFilterGroup group, Set<String> knownFields) {
        if (group == null) {
            return null;
        }
        List<AstFilter> conditions = group.conditions() == null ? List.of() : group.conditions().stream()
                .filter(f -> f == null || f.field() == null || knownFields.contains(f.field()))
                .toList();
        List<AstFilterGroup> groups = group.groups() == null ? List.of() : group.groups().stream()
                .map(g -> pruneUnknownFilters(g, knownFields))
                .toList();
        return new AstFilterGroup(group.op(), conditions, groups);
    }

    private String resolveQuestion(EngineSession session) {
        if (session == null) {
            return "";
        }
        String q = trimToNull(session.getResolvedUserInput());
        if (q == null) {
            q = trimToNull(session.getStandaloneQuery());
        }
        if (q == null) {
            q = trimToNull(session.getUserText());
        }
        return q == null ? "" : q;
    }

    private SemanticIntentRule resolveIntentRule(String question, SemanticModel model) {
        if (question == null || question.isBlank() || model == null || model.intentRules() == null || model.intentRules().isEmpty()) {
            return null;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (SemanticIntentRule rule : model.intentRules().values()) {
            if (rule == null) continue;
            boolean anyMatch = rule.matchAny() != null && !rule.matchAny().isEmpty()
                    && rule.matchAny().stream().filter(Objects::nonNull)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(lower::contains);
            if (!anyMatch) continue;
            boolean mustContainOk = true;
            if (rule.mustContain() != null && !rule.mustContain().isEmpty()) {
                for (String term : rule.mustContain()) {
                    if (term == null || term.isBlank()) {
                        continue;
                    }
                    if (!lower.contains(term.toLowerCase(Locale.ROOT))) {
                        mustContainOk = false;
                        break;
                    }
                }
            }
            if (mustContainOk) {
                return rule;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private void assertNormalizationDidNotDropCriticalIntent(SemanticQueryAstV1 original,
                                                              SemanticQueryAstV1 normalized,
                                                              String targetEntity) {
        if (original == null || normalized == null) {
            return;
        }
        boolean originalHasPredicate = hasPredicates(original.filters(), original.where());
        boolean normalizedHasPredicate = hasPredicates(normalized.filters(), normalized.where());
        if (originalHasPredicate && !normalizedHasPredicate) {
            throw new IllegalStateException(
                    "AST normalization removed all predicates for entity '" + safeEntity(targetEntity, normalized.entity())
                            + "'. Likely entity-field mismatch; retry with aligned entity fields.");
        }

        boolean originalHasProjectionIntent = hasProjectionIntent(original.select(), original.projections());
        boolean normalizedHasProjectionIntent = hasProjectionIntent(normalized.select(), normalized.projections());
        boolean normalizedHasMetricIntent = normalized.metrics() != null && !normalized.metrics().isEmpty();
        if (originalHasProjectionIntent && !normalizedHasProjectionIntent) {
            // Metric-only ASTs are valid (e.g., "show billbank_gap_count"), so allow
            // projection pruning when metric intent is preserved.
            if (normalizedHasMetricIntent) {
                return;
            }
            throw new IllegalStateException(
                    "AST normalization removed all selected fields for entity '" + safeEntity(targetEntity, normalized.entity())
                            + "'. Likely entity-field mismatch; retry with aligned entity fields.");
        }
    }

    private String safeEntity(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "UNKNOWN";
    }

    private boolean hasPredicates(List<AstFilter> filters, AstFilterGroup group) {
        boolean hasFlatFilters = filters != null && filters.stream().anyMatch(Objects::nonNull);
        return hasFlatFilters || hasFilterGroupConditions(group);
    }

    private boolean hasFilterGroupConditions(AstFilterGroup group) {
        if (group == null) {
            return false;
        }
        boolean hasConditions = group.conditions() != null && group.conditions().stream().anyMatch(Objects::nonNull);
        if (hasConditions) {
            return true;
        }
        if (group.groups() == null || group.groups().isEmpty()) {
            return false;
        }
        for (AstFilterGroup child : group.groups()) {
            if (hasFilterGroupConditions(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProjectionIntent(List<String> select, List<AstProjection> projections) {
        boolean hasSelect = select != null && select.stream().anyMatch(s -> s != null && !s.isBlank());
        if (hasSelect) {
            return true;
        }
        if (projections == null || projections.isEmpty()) {
            return false;
        }
        return projections.stream().anyMatch(p -> p != null && p.field() != null && !p.field().isBlank());
    }
}
