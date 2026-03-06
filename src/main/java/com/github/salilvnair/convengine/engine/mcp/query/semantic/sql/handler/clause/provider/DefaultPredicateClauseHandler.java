package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstLogicalOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstPredicateHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultPredicateClauseHandler implements AstPredicateHandler {
    private static final Pattern TABLE_TOKEN_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.");

    @FunctionalInterface
    public interface CorrelatedFieldResolver {
        Field<Object> resolve(String fieldRef);
    }

    private final AstOperatorHandlerRegistry operatorHandlerRegistry;

    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getQuery() != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        var ast = plan.getAst();
        var entity = plan.getEntity();
        var model = plan.getModel();
        var aliasByTable = plan.getAliasByTable();
        var params = plan.getParams();
        var paramIdx = plan.getParamIdx();

        List<Condition> where = new ArrayList<>();
        if (ast.where() != null && !ast.where().isEmpty()) {
            where.add(toConditionGroup(ast.where(), entity, aliasByTable, params, paramIdx, false, model));
        }
        appendTimeRangeWhere(plan, where);
        if (!where.isEmpty()) {
            plan.getQuery().addConditions(where);
        }

        if (ast.having() != null && !ast.having().isEmpty()) {
            Condition having = toConditionGroup(ast.having(), entity, aliasByTable, params, paramIdx, true, model);
            plan.getQuery().addHaving(having);
        }
    }

    public String renderFilterGroupSql(CanonicalFilterGroup group,
                                       SemanticEntity entity,
                                       Map<String, String> aliases,
                                       Map<String, Object> params,
                                       int[] paramIdx,
                                       SemanticModel model) {
        return renderFilterGroupSql(group, entity, aliases, params, paramIdx, model, null);
    }

    public String renderFilterGroupSql(CanonicalFilterGroup group,
                                       SemanticEntity entity,
                                       Map<String, String> aliases,
                                       Map<String, Object> params,
                                       int[] paramIdx,
                                       SemanticModel model,
                                       CorrelatedFieldResolver correlatedFieldResolver) {
        if (group == null || group.isEmpty()) {
            return "";
        }
        List<String> predicates = new ArrayList<>();
        for (CanonicalFilter condition : group.conditions()) {
            String p = renderFilterSql(condition, entity, aliases, params, paramIdx, model, correlatedFieldResolver);
            if (!p.isBlank()) {
                predicates.add(p);
            }
        }
        for (CanonicalFilterGroup child : group.groups()) {
            String childSql = renderFilterGroupSql(child, entity, aliases, params, paramIdx, model, correlatedFieldResolver);
            if (!childSql.isBlank()) {
                predicates.add("(" + childSql + ")");
            }
        }
        if (predicates.isEmpty()) {
            return "";
        }
        AstLogicalOperator op = group.op() == null ? AstLogicalOperator.AND : group.op();
        if (op == AstLogicalOperator.NOT) {
            return "NOT (" + predicates.get(0) + ")";
        }
        String joiner = op == AstLogicalOperator.OR ? " OR " : " AND ";
        return String.join(joiner, predicates);
    }

    public String renderFilterSql(CanonicalFilter filter,
                                  SemanticEntity entity,
                                  Map<String, String> aliases,
                                  Map<String, Object> params,
                                  int[] paramIdx,
                                  SemanticModel model) {
        return renderFilterSql(filter, entity, aliases, params, paramIdx, model, null);
    }

    public String renderFilterSql(CanonicalFilter filter,
                                  SemanticEntity entity,
                                  Map<String, String> aliases,
                                  Map<String, Object> params,
                                  int[] paramIdx,
                                  SemanticModel model,
                                  CorrelatedFieldResolver correlatedFieldResolver) {
        if (filter == null || filter.field() == null || filter.field().isBlank()) {
            return "";
        }
        String metricSql = resolveMetricSql(filter.field(), model, aliases);
        Field<Object> field = metricSql != null ? DSL.field(DSL.sql(metricSql)) : resolveEntityField(entity, filter.field(), aliases);
        if (field == null) {
            return "";
        }
        AstOperator op = Objects.requireNonNullElse(filter.operator(), AstOperator.EQ);
        if (correlatedFieldResolver != null && filter.value() instanceof String valueRef && valueRef.startsWith("$") && valueRef.length() > 1) {
            Field<Object> rhsField = correlatedFieldResolver.resolve(valueRef.substring(1));
            if (rhsField != null) {
                String token = operatorSqlToken(op);
                if (token != null) {
                    return field + " " + token + " " + rhsField;
                }
            }
        }
        AstOperatorHandler operatorHandler = operatorHandlerRegistry.resolve(op);
        if (operatorHandler == null) {
            throw new IllegalStateException("Unsupported AST operator: " + op);
        }
        OperatorHandlerContext operatorContext = new OperatorHandlerContext(params, paramIdx, this::normalizeMacroValue);
        String sql = operatorHandler.buildSql(field, filter.value(), op, operatorContext);
        return sql == null ? "" : sql;
    }

    public String resolveMetricSql(String metricField, SemanticModel model) {
        if (metricField == null || model == null || model.metrics() == null) {
            return null;
        }
        var metric = model.metrics().get(metricField);
        if (metric == null || metric.sql() == null || metric.sql().isBlank()) {
            return null;
        }
        return metric.sql();
    }

    public String resolveMetricSql(String metricField, SemanticModel model, Map<String, String> aliasByTable) {
        String raw = resolveMetricSql(metricField, model);
        if (raw == null) {
            return null;
        }
        return qualifyMetricSql(raw, aliasByTable);
    }

    public Field<Object> resolveEntityField(SemanticEntity entity, String fieldName, Map<String, String> aliasByTable) {
        if (entity == null || entity.fields() == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        SemanticField field = entity.fields().get(fieldName);
        if (field == null || field.column() == null || field.column().isBlank()) {
            return null;
        }
        return columnField(field.column(), aliasByTable);
    }

    private String operatorSqlToken(AstOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case LIKE -> "LIKE";
            case ILIKE -> "ILIKE";
            default -> null;
        };
    }

    public String buildCorrelationPredicate(SemanticEntity outerEntity,
                                            Map<String, String> outerAliases,
                                            SemanticEntity subEntity,
                                            String subAlias,
                                            SemanticModel model) {
        if (outerEntity == null || subEntity == null || model == null || model.relationships() == null) {
            return "";
        }
        String outerTable = outerEntity.tables() == null ? null : outerEntity.tables().primary();
        String subTable = subEntity.tables() == null ? null : subEntity.tables().primary();
        if (outerTable == null || subTable == null) {
            return "";
        }
        for (var relationship : model.relationships()) {
            if (relationship == null || relationship.from() == null || relationship.to() == null) {
                continue;
            }
            if (outerTable.equals(relationship.from().table()) && subTable.equals(relationship.to().table())) {
                String outerAlias = outerAliases.getOrDefault(outerTable, outerTable);
                return outerAlias + "." + relationship.from().column() + " = " + subAlias + "." + relationship.to().column();
            }
            if (outerTable.equals(relationship.to().table()) && subTable.equals(relationship.from().table())) {
                String outerAlias = outerAliases.getOrDefault(outerTable, outerTable);
                return outerAlias + "." + relationship.to().column() + " = " + subAlias + "." + relationship.from().column();
            }
        }
        return "";
    }

    private Condition toConditionGroup(CanonicalFilterGroup group,
                                       SemanticEntity entity,
                                       Map<String, String> aliasByTable,
                                       Map<String, Object> params,
                                       int[] paramIdx,
                                       boolean having,
                                       SemanticModel model) {
        List<Condition> expressions = new ArrayList<>();
        for (CanonicalFilter filter : group.conditions()) {
            Condition c = toCondition(filter, entity, aliasByTable, params, paramIdx, having, model);
            if (c != null) {
                expressions.add(c);
            }
        }
        for (CanonicalFilterGroup child : group.groups()) {
            if (child == null || child.isEmpty()) {
                continue;
            }
            expressions.add(toConditionGroup(child, entity, aliasByTable, params, paramIdx, having, model));
        }
        if (expressions.isEmpty()) {
            return DSL.trueCondition();
        }
        AstLogicalOperator op = group.op() == null ? AstLogicalOperator.AND : group.op();
        return switch (op) {
            case OR -> DSL.or(expressions);
            case NOT -> DSL.not(expressions.get(0));
            default -> DSL.and(expressions);
        };
    }

    private Condition toCondition(CanonicalFilter filter,
                                  SemanticEntity entity,
                                  Map<String, String> aliasByTable,
                                  Map<String, Object> params,
                                  int[] paramIdx,
                                  boolean having,
                                  SemanticModel model) {
        if (filter == null || filter.field() == null || filter.field().isBlank()) {
            return null;
        }
        Field<Object> target;
        String metricSql = resolveMetricSql(filter.field(), model, aliasByTable);
        if (metricSql != null) {
            target = DSL.field(DSL.sql(metricSql));
        } else {
            target = resolveEntityField(entity, filter.field(), aliasByTable);
        }
        if (target == null) {
            return null;
        }
        AstOperator op = Objects.requireNonNullElse(filter.operator(), AstOperator.EQ);
        AstOperatorHandler operatorHandler = operatorHandlerRegistry.resolve(op);
        if (operatorHandler == null) {
            throw new IllegalStateException("Unsupported AST operator: " + op);
        }
        OperatorHandlerContext operatorContext = new OperatorHandlerContext(params, paramIdx, this::normalizeMacroValue);
        return operatorHandler.buildCondition(target, filter.value(), op, operatorContext);
    }

    private void appendTimeRangeWhere(CompileWorkPlan plan, List<Condition> where) {
        var ast = plan.getAst();
        var entity = plan.getEntity();
        var aliasByTable = plan.getAliasByTable();
        var params = plan.getParams();
        var paramIdx = plan.getParamIdx();

        if (ast.timeRange() == null || ast.timeRange().field() == null || ast.timeRange().field().isBlank()) {
            return;
        }
        SemanticField field = entity.fields().get(ast.timeRange().field());
        if (field == null || field.column() == null || field.column().isBlank()) {
            return;
        }
        Field<Object> colField = columnField(field.column(), aliasByTable);
        if (ast.timeRange().from() != null && !ast.timeRange().from().isBlank()) {
            String p = "p" + paramIdx[0]++;
            Object value = normalizeMacroValue(ast.timeRange().from());
            params.put(p, value);
            where.add(DSL.condition("{0} >= {1}", colField, DSL.param(p, value)));
        }
        if (ast.timeRange().to() != null && !ast.timeRange().to().isBlank()) {
            String p = "p" + paramIdx[0]++;
            Object value = normalizeMacroValue(ast.timeRange().to());
            params.put(p, value);
            where.add(DSL.condition("{0} <= {1}", colField, DSL.param(p, value)));
        }
    }

    private Field<Object> columnField(String tableDotColumn, Map<String, String> aliasByTable) {
        String[] parts = tableDotColumn.split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid semantic field column format: " + tableDotColumn);
        }
        String alias = aliasByTable.getOrDefault(parts[0], parts[0]);
        return DSL.field(DSL.name(alias, parts[1]));
    }

    private Object normalizeMacroValue(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String v = text.trim().toLowerCase(Locale.ROOT);
        ZoneOffset zone = ZoneOffset.UTC;
        OffsetDateTime now = OffsetDateTime.now(zone);
        if ("now".equals(v)) {
            return now;
        }
        if ("today".equals(v)) {
            return ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toOffsetDateTime();
        }
        if ("last_7d".equals(v)) {
            return now.minusDays(7);
        }
        if (v.matches("now-\\d+h")) {
            int h = parseInt(v.substring(4, v.length() - 1), 0);
            return now.minusHours(Math.max(h, 0));
        }
        if (v.matches("now-\\d+d")) {
            int d = parseInt(v.substring(4, v.length() - 1), 0);
            return now.minusDays(Math.max(d, 0));
        }
        if (v.matches("last_\\d+d")) {
            int d = parseInt(v.substring(5, v.length() - 1), 0);
            return now.minusDays(Math.max(d, 0));
        }

        // If user/LLM sends ISO timestamp text, bind as temporal type (not VARCHAR),
        // so comparisons against timestamptz/timestamp columns work in PostgreSQL.
        OffsetDateTime parsed = parseIsoDateTime(text.trim());
        if (parsed != null) {
            return parsed;
        }
        return value;
    }

    private OffsetDateTime parseIsoDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        return null;
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String qualifyMetricSql(String sql, Map<String, String> aliasByTable) {
        if (sql == null || sql.isBlank() || aliasByTable == null || aliasByTable.isEmpty()) {
            return sql;
        }
        Matcher matcher = TABLE_TOKEN_PATTERN.matcher(sql);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String table = matcher.group(1);
            String alias = aliasByTable.get(table);
            if (alias == null || alias.isBlank()) {
                continue;
            }
            matcher.appendReplacement(out, alias + ".");
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
