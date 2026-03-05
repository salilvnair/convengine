package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubquerySpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstSubqueryHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultSubqueryClauseHandler implements AstSubqueryHandler {

    private final DefaultPredicateClauseHandler predicateClauseHandler;

    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getQuery() != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        if (plan.getAst().subqueryFilters() == null || plan.getAst().subqueryFilters().isEmpty()) {
            return;
        }
        List<Condition> conditions = new ArrayList<>();
        for (CanonicalSubqueryFilter filter : plan.getAst().subqueryFilters()) {
            Condition c = toSubqueryFilterCondition(filter, plan);
            if (c != null) {
                conditions.add(c);
            }
        }
        if (!conditions.isEmpty()) {
            plan.getQuery().addConditions(conditions);
        }
    }

    private Condition toSubqueryFilterCondition(CanonicalSubqueryFilter filter, CompileWorkPlan plan) {
        if (filter == null || filter.field() == null || filter.field().isBlank() || filter.subquery() == null) {
            return null;
        }
        Field<Object> outerField = predicateClauseHandler.resolveEntityField(plan.getEntity(), filter.field(), plan.getAliasByTable());
        if (outerField == null) {
            return null;
        }
        String subquerySql = buildScalarSubquerySql(filter.subquery(), plan);
        if (subquerySql.isBlank()) {
            return null;
        }
        AstOperator op = Objects.requireNonNullElse(filter.operator(), AstOperator.EQ);
        String token = switch (op) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> "=";
        };
        return DSL.condition("{0} " + token + " (" + subquerySql + ")", outerField);
    }

    private String buildScalarSubquerySql(CanonicalSubquerySpec spec, CompileWorkPlan plan) {
        if (spec == null || spec.entity() == null || spec.selectField() == null) {
            return "";
        }
        SemanticEntity subEntity = plan.getModel().entities().get(spec.entity());
        if (subEntity == null || subEntity.tables() == null || subEntity.tables().primary() == null) {
            return "";
        }
        String subTable = subEntity.tables().primary();
        String subAlias = "sq" + plan.getParamIdx()[0];
        SemanticField select = subEntity.fields().get(spec.selectField());
        if (select == null || select.column() == null || select.column().isBlank()) {
            return "";
        }
        Field<Object> selectField = columnField(select.column(), Map.of(subTable, subAlias));
        StringBuilder sql = new StringBuilder("SELECT ").append(selectField)
                .append(" FROM ").append(subTable).append(" ").append(subAlias);
        List<String> wherePredicates = new ArrayList<>();
        String correlation = predicateClauseHandler.buildCorrelationPredicate(
                plan.getEntity(), plan.getAliasByTable(), subEntity, subAlias, plan.getModel());
        if (!correlation.isBlank()) {
            wherePredicates.add(correlation);
        }
        String whereSql = predicateClauseHandler.renderFilterGroupSql(
                spec.where(), subEntity, Map.of(subTable, subAlias), plan.getParams(), plan.getParamIdx(), plan.getModel());
        if (!whereSql.isBlank()) {
            wherePredicates.add(whereSql);
        }
        if (!wherePredicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", wherePredicates));
        }
        if (spec.groupBy() != null && !spec.groupBy().isEmpty()) {
            List<String> group = new ArrayList<>();
            for (String fieldName : spec.groupBy()) {
                SemanticField field = subEntity.fields().get(fieldName);
                if (field != null && field.column() != null && !field.column().isBlank()) {
                    group.add(columnField(field.column(), Map.of(subTable, subAlias)).toString());
                }
            }
            if (!group.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", group));
            }
        }
        String havingSql = predicateClauseHandler.renderFilterGroupSql(
                spec.having(), subEntity, Map.of(subTable, subAlias), plan.getParams(), plan.getParamIdx(), plan.getModel());
        if (!havingSql.isBlank()) {
            sql.append(" HAVING ").append(havingSql);
        }
        sql.append(" LIMIT ").append(Math.max(spec.limit(), 1));
        return sql.toString();
    }

    private Field<Object> columnField(String tableDotColumn, Map<String, String> aliasByTable) {
        String[] parts = tableDotColumn.split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid semantic field column format: " + tableDotColumn);
        }
        String alias = aliasByTable.getOrDefault(parts[0], parts[0]);
        return DSL.field(DSL.name(alias, parts[1]));
    }
}

