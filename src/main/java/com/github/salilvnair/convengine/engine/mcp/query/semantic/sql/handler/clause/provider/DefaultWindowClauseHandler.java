package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core.AstWindowFunctionHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core.AstWindowFunctionHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstWindowHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultWindowClauseHandler implements AstWindowHandler {

    private final DefaultPredicateClauseHandler predicateClauseHandler;
    private final AstWindowFunctionHandlerRegistry windowFunctionHandlerRegistry;

    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getQuery() != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        var ast = plan.getAst();
        if (ast.windows() == null || ast.windows().isEmpty()) {
            return;
        }
        int idx = 1;
        for (CanonicalWindowSpec window : ast.windows()) {
            if (window == null || window.function() == null) {
                continue;
            }
            String alias = window.name() == null || window.name().isBlank() ? "rowNumber" + idx++ : window.name();
            String over = buildWindowOverClause(window, plan);
            AstWindowFunctionHandler functionHandler = windowFunctionHandlerRegistry.resolve(window.function());
            if (functionHandler == null) {
                throw new IllegalStateException(SemanticSqlConstants.ERROR_UNSUPPORTED_WINDOW_FUNCTION_PREFIX + window.function());
            }
            String expression = functionHandler.renderExpression(over, window);
            plan.getQuery().addSelect(DSL.field(DSL.sql(expression)).as(alias));
        }
    }

    private String buildWindowOverClause(CanonicalWindowSpec window, CompileWorkPlan plan) {
        List<String> segments = new ArrayList<>();
        Set<String> groupedFields = groupedFields(plan);
        if (window.partitionBy() != null && !window.partitionBy().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String fieldName : window.partitionBy()) {
                Field<Object> field = predicateClauseHandler.resolveEntityField(plan.getEntity(), fieldName, plan.getAliasByTable());
                if (field != null) {
                    parts.add(groupSafeFieldSql(field, fieldName, groupedFields));
                }
            }
            if (!parts.isEmpty()) {
                segments.add("PARTITION BY " + String.join(", ", parts));
            }
        }
        if (window.orderBy() != null && !window.orderBy().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (CanonicalSort sort : window.orderBy()) {
                if (sort == null || sort.field() == null || sort.field().isBlank()) {
                    continue;
                }
                Field<Object> field = predicateClauseHandler.resolveEntityField(plan.getEntity(), sort.field(), plan.getAliasByTable());
                if (field == null) {
                    continue;
                }
                String direction = sort.direction() == null ? "ASC" : sort.direction().name();
                String nulls = sort.nulls() == null ? "" : (" NULLS " + sort.nulls().name());
                parts.add(groupSafeFieldSql(field, sort.field(), groupedFields) + " " + direction + nulls);
            }
            if (!parts.isEmpty()) {
                segments.add("ORDER BY " + String.join(", ", parts));
            }
        }
        return String.join(" ", segments);
    }

    private Set<String> groupedFields(CompileWorkPlan plan) {
        Set<String> grouped = new LinkedHashSet<>();
        if (plan == null || plan.getAst() == null || plan.getAst().groupBy() == null) {
            return grouped;
        }
        for (String name : plan.getAst().groupBy()) {
            if (name != null && !name.isBlank()) {
                grouped.add(name.trim());
            }
        }
        return grouped;
    }

    private String groupSafeFieldSql(Field<Object> field, String semanticFieldName, Set<String> groupedFields) {
        if (field == null) {
            return "";
        }
        if (groupedFields == null || groupedFields.isEmpty()) {
            return field.toString();
        }
        if (semanticFieldName != null && groupedFields.contains(semanticFieldName)) {
            return field.toString();
        }
        // Query has GROUP BY and this field is not grouped; wrap in aggregate to remain SQL-valid.
        return DSL.max(field).toString();
    }
}
