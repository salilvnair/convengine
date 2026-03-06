package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstMetricHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultMetricClauseHandler implements AstMetricHandler {

    private final DefaultPredicateClauseHandler predicateClauseHandler;

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

        if (!ast.groupBy().isEmpty()) {
            List<Field<?>> gb = new ArrayList<>();
            for (String fieldName : ast.groupBy()) {
                var field = entity.fields().get(fieldName);
                if (field != null && field.column() != null) {
                    gb.add(columnField(field.column(), aliasByTable));
                }
            }
            if (!gb.isEmpty()) {
                plan.getQuery().addGroupBy(gb);
            }
        }

        if (!ast.metrics().isEmpty() && model.metrics() != null) {
            for (String metricName : ast.metrics()) {
                String metricSql = predicateClauseHandler.resolveMetricSql(metricName, model, aliasByTable);
                if (metricSql == null || metricSql.isBlank()) {
                    continue;
                }
                plan.getQuery().addSelect(DSL.field(DSL.sql(metricSql)).as(metricName));
            }
        }

        if (!ast.sort().isEmpty()) {
            List<SortField<?>> sorts = new ArrayList<>();
            for (CanonicalSort sort : ast.sort()) {
                if (sort == null || sort.field() == null) {
                    continue;
                }
                String metricSql = predicateClauseHandler.resolveMetricSql(sort.field(), model, aliasByTable);
                Field<Object> sortField = metricSql != null
                        ? DSL.field(DSL.sql(metricSql))
                        : predicateClauseHandler.resolveEntityField(entity, sort.field(), aliasByTable);
                if (sortField == null) {
                    continue;
                }
                SortOrder order = sort.direction() == null || sort.direction().name().equalsIgnoreCase("ASC")
                        ? SortOrder.ASC : SortOrder.DESC;
                SortField<Object> sf = sortField.sort(order);
                if (sort.nulls() != null) {
                    sf = "FIRST".equalsIgnoreCase(sort.nulls().name()) ? sf.nullsFirst() : sf.nullsLast();
                }
                sorts.add(sf);
            }
            if (!sorts.isEmpty()) {
                plan.getQuery().addOrderBy(sorts);
            }
        }
    }

    private Field<Object> columnField(String tableDotColumn, java.util.Map<String, String> aliasByTable) {
        String[] parts = tableDotColumn.split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid semantic field column format: " + tableDotColumn);
        }
        String alias = aliasByTable.getOrDefault(parts[0], parts[0]);
        return DSL.field(DSL.name(alias, parts[1]));
    }
}
