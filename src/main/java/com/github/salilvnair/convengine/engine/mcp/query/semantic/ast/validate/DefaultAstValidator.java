package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubquerySpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultAstValidator implements AstSemanticValidator {

    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<List<AstValidationInterceptor>> interceptorsProvider;

    @Override
    public AstValidationResult validate(CanonicalAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session) {
        List<AstValidationInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (AstValidationInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeValidate(ast, model, joinPathPlan, session);
            }
        }
        try {
            List<String> errors = new ArrayList<>();
            if (ast == null) {
                errors.add("ast is null");
                return new AstValidationResult(false, errors);
            }
            if (ast.entity() == null || ast.entity().isBlank()) {
                errors.add("entity is required");
                return new AstValidationResult(false, errors);
            }
            SemanticEntity entity = model.entities().get(ast.entity());
            if (entity == null) {
                errors.add("entity not found: " + ast.entity());
                return new AstValidationResult(false, errors);
            }
            Set<String> fieldNames = entity.fields().keySet();
            Set<String> knownMetrics = model.metrics() == null ? Set.of() : model.metrics().keySet();

            ast.projections().forEach(p -> {
                if (p == null || p.field() == null || p.field().isBlank()) {
                    return;
                }
                if (!fieldNames.contains(p.field())) {
                    errors.add("unknown select field: " + p.field());
                }
            });
            ast.groupBy().forEach(group -> {
                if (group != null && !group.isBlank() && !fieldNames.contains(group)) {
                    errors.add("unknown group_by field: " + group);
                }
            });
            ast.sort().forEach(sort -> {
                if (sort == null || sort.field() == null || sort.field().isBlank()) {
                    return;
                }
                if (!fieldNames.contains(sort.field())) {
                    errors.add("unknown sort field: " + sort.field());
                }
            });
            ast.metrics().forEach(metric -> {
                if (metric == null || metric.isBlank()) {
                    return;
                }
                if (!knownMetrics.contains(metric)) {
                    errors.add("unknown metric: " + metric);
                }
            });
            validateExistsBlocks(errors, ast.existsBlocks(), model);
            validateSubqueryFilters(errors, ast.subqueryFilters(), fieldNames, model);
            validateWindows(errors, ast.windows(), fieldNames);

            validateFilterGroup(errors, ast.where(), fieldNames, entity.fields(), knownMetrics, false);
            validateFilterGroup(errors, ast.having(), fieldNames, entity.fields(), knownMetrics, true);

            if (!ast.metrics().isEmpty() && !ast.projections().isEmpty() && ast.groupBy().isEmpty()) {
                errors.add("non-grouped selected field with metrics is not allowed");
            }
            if (!ast.metrics().isEmpty() && !ast.projections().isEmpty()) {
                for (var p : ast.projections()) {
                    if (p == null || p.field() == null || p.field().isBlank()) {
                        continue;
                    }
                    if (!ast.groupBy().contains(p.field())) {
                        errors.add("selected field must be present in group_by when metrics are requested: " + p.field());
                    }
                }
            }

            ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
            int maxLimit = Math.max(cfg.getMaxLimit(), Math.max(cfg.getDefaultLimit(), 1));
            if (ast.limit() > maxLimit) {
                errors.add("limit exceeds max: " + ast.limit());
            }
            if (ast.offset() < 0) {
                errors.add("offset cannot be negative");
            }
            if (joinPathPlan != null && joinPathPlan.unresolvedTables() != null && !joinPathPlan.unresolvedTables().isEmpty()) {
                errors.add("join path unresolved tables: " + joinPathPlan.unresolvedTables());
            }

            AstValidationResult result = new AstValidationResult(errors.isEmpty(), List.copyOf(errors));
            AstValidationResult current = result;
            for (AstValidationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    current = interceptor.afterValidate(current, session);
                }
            }
            return current;
        } catch (Exception ex) {
            for (AstValidationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(ast, session, ex);
                }
            }
            throw ex;
        }
    }

    private void validateExistsBlocks(List<String> errors, List<CanonicalExistsBlock> existsBlocks, SemanticModel model) {
        if (existsBlocks == null || existsBlocks.isEmpty()) {
            return;
        }
        for (CanonicalExistsBlock block : existsBlocks) {
            if (block == null || block.entity() == null || block.entity().isBlank()) {
                errors.add("exists block entity is required");
                continue;
            }
            SemanticEntity entity = model.entities().get(block.entity());
            if (entity == null) {
                errors.add("exists entity not found: " + block.entity());
                continue;
            }
            validateFilterGroup(errors, block.where(), entity.fields().keySet(), entity.fields(), Set.of(), false);
        }
    }

    private void validateSubqueryFilters(List<String> errors,
                                         List<CanonicalSubqueryFilter> filters,
                                         Set<String> outerFieldNames,
                                         SemanticModel model) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (CanonicalSubqueryFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            if (filter.field() == null || !outerFieldNames.contains(filter.field())) {
                errors.add("unknown subquery filter field: " + filter.field());
            }
            CanonicalSubquerySpec subquery = filter.subquery();
            if (subquery == null) {
                errors.add("subquery specification is required");
                continue;
            }
            if (subquery.entity() == null || subquery.entity().isBlank()) {
                errors.add("subquery entity is required");
                continue;
            }
            SemanticEntity subEntity = model.entities().get(subquery.entity());
            if (subEntity == null) {
                errors.add("subquery entity not found: " + subquery.entity());
                continue;
            }
            if (subquery.selectField() == null || !subEntity.fields().containsKey(subquery.selectField())) {
                errors.add("subquery select field not found: " + subquery.selectField());
            }
            validateFilterGroup(errors, subquery.where(), subEntity.fields().keySet(), subEntity.fields(), Set.of(), false);
            validateFilterGroup(errors, subquery.having(), subEntity.fields().keySet(), subEntity.fields(), Set.of(), true);
            if (subquery.limit() <= 0) {
                errors.add("subquery limit must be > 0");
            }
            for (String gb : subquery.groupBy()) {
                if (gb == null || !subEntity.fields().containsKey(gb)) {
                    errors.add("subquery group_by field not found: " + gb);
                }
            }
        }
    }

    private void validateWindows(List<String> errors, List<CanonicalWindowSpec> windows, Set<String> fieldNames) {
        if (windows == null || windows.isEmpty()) {
            return;
        }
        for (CanonicalWindowSpec window : windows) {
            if (window == null) {
                continue;
            }
            if (window.function() == null || !"ROW_NUMBER".equalsIgnoreCase(window.function())) {
                errors.add("unsupported window function: " + window.function());
            }
            for (String partition : window.partitionBy()) {
                if (partition != null && !fieldNames.contains(partition)) {
                    errors.add("window partition_by field not found: " + partition);
                }
            }
            window.orderBy().forEach(sort -> {
                if (sort != null && sort.field() != null && !fieldNames.contains(sort.field())) {
                    errors.add("window order_by field not found: " + sort.field());
                }
            });
        }
    }

    private void validateFilterGroup(List<String> errors,
                                     CanonicalFilterGroup group,
                                     Set<String> fieldNames,
                                     java.util.Map<String, SemanticField> fields,
                                     Set<String> metrics,
                                     boolean havingContext) {
        if (group == null || group.isEmpty()) {
            return;
        }
        if (group.conditions().isEmpty() && group.groups().isEmpty()) {
            errors.add("empty boolean group is not allowed");
            return;
        }
        if (group.op() == AstLogicalOperator.NOT && (group.conditions().size() + group.groups().size()) != 1) {
            errors.add("NOT group must contain exactly one child");
        }
        for (CanonicalFilter filter : group.conditions()) {
            validateFilter(errors, filter, fieldNames, fields, metrics, havingContext);
        }
        for (CanonicalFilterGroup child : group.groups()) {
            validateFilterGroup(errors, child, fieldNames, fields, metrics, havingContext);
        }
    }

    private void validateFilter(List<String> errors,
                                CanonicalFilter filter,
                                Set<String> fieldNames,
                                java.util.Map<String, SemanticField> fields,
                                Set<String> metrics,
                                boolean havingContext) {
        if (filter == null) {
            return;
        }
        if (filter.field() == null || filter.field().isBlank()) {
            errors.add("filter field is required");
            return;
        }
        boolean metricField = metrics.contains(filter.field());
        if (havingContext) {
            if (!metricField && !fieldNames.contains(filter.field())) {
                errors.add("unknown having field: " + filter.field());
            }
        } else {
            if (metricField) {
                errors.add("metric filter is allowed only in having: " + filter.field());
            } else if (!fieldNames.contains(filter.field())) {
                errors.add("unknown filter field: " + filter.field());
            }
        }

        AstOperator op = Objects.requireNonNullElse(filter.operator(), AstOperator.EQ);
        Object value = filter.value();
        switch (op) {
            case IS_NULL, IS_NOT_NULL -> {
                if (value != null) {
                    errors.add("operator " + op + " does not accept value");
                }
            }
            case IN, NOT_IN -> {
                if (!(value instanceof List<?> list) || list.isEmpty()) {
                    errors.add("operator " + op + " requires non-empty array value");
                }
            }
            case BETWEEN -> {
                if (!(value instanceof List<?> list) || list.size() != 2) {
                    errors.add("operator BETWEEN requires exactly two values");
                }
            }
            case WITHIN_LAST -> {
                if (value == null) {
                    errors.add("operator WITHIN_LAST requires value");
                } else if (!isWithinLastValueValid(value)) {
                    errors.add("operator WITHIN_LAST requires positive amount (examples: 24, '24h', 'last 24 hr', {amount:24,unit:'hour'})");
                }
            }
            default -> {
                if (value == null) {
                    errors.add("operator " + op + " requires value");
                }
            }
        }

        if (!metricField && fields != null && fields.containsKey(filter.field())) {
            SemanticField field = fields.get(filter.field());
            String type = field == null || field.type() == null ? "" : field.type().toLowerCase(Locale.ROOT);
            if ((op == AstOperator.LIKE || op == AstOperator.ILIKE) && !(type.contains("char") || type.contains("text") || type.contains("string"))) {
                errors.add("operator " + op + " requires string/text field: " + filter.field());
            }
            if (op == AstOperator.WITHIN_LAST
                    && !(type.contains("time") || type.contains("date") || type.contains("timestamp"))) {
                errors.add("operator WITHIN_LAST requires date/timestamp field: " + filter.field());
            }
            if (field != null && field.allowedValues() != null && !field.allowedValues().isEmpty()) {
                if (op == AstOperator.EQ || op == AstOperator.NE) {
                    if (!isAllowedFilterValue(value, field.allowedValues())) {
                        errors.add("value not in allowed_values for field " + filter.field() + ": " + value);
                    }
                } else if (op == AstOperator.IN || op == AstOperator.NOT_IN) {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (!isAllowedFilterValue(item, field.allowedValues())) {
                                errors.add("value not in allowed_values for field " + filter.field() + ": " + item);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isAllowedFilterValue(Object value, List<String> allowedValues) {
        if (value == null || allowedValues == null || allowedValues.isEmpty()) {
            return value == null;
        }
        String candidate = String.valueOf(value).trim();
        for (String allowed : allowedValues) {
            if (allowed == null) {
                continue;
            }
            if (candidate.equalsIgnoreCase(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinLastValueValid(Object value) {
        if (value instanceof Number number) {
            return number.longValue() > 0;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            Object amount = map.get("amount");
            if (amount == null) {
                amount = map.get("value");
            }
            if (amount == null) {
                amount = map.get("n");
            }
            if (amount == null) {
                return false;
            }
            return isWithinLastValueValid(amount);
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT)
                    .replace("within", "")
                    .replace("last", "")
                    .replace("_", " ")
                    .trim();
            if (normalized.isBlank()) {
                return false;
            }
            String digits = normalized.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return false;
            }
            try {
                return Long.parseLong(digits) > 0;
            } catch (Exception ex) {
                return false;
            }
        }
        return false;
    }
}
