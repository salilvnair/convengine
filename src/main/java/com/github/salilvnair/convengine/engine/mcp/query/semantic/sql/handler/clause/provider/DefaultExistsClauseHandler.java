package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstExistsHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultExistsClauseHandler implements AstExistsHandler {

    private final DefaultPredicateClauseHandler predicateClauseHandler;

    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getQuery() != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        if (plan.getAst().existsBlocks() == null || plan.getAst().existsBlocks().isEmpty()) {
            return;
        }
        List<Condition> conditions = new ArrayList<>();
        for (CanonicalExistsBlock existsBlock : plan.getAst().existsBlocks()) {
            Condition c = toExistsCondition(existsBlock, plan);
            if (c != null) {
                conditions.add(c);
            }
        }
        if (!conditions.isEmpty()) {
            plan.getQuery().addConditions(conditions);
        }
    }

    private Condition toExistsCondition(CanonicalExistsBlock existsBlock, CompileWorkPlan plan) {
        if (existsBlock == null || existsBlock.entity() == null || existsBlock.entity().isBlank()) {
            return null;
        }
        SemanticEntity subEntity = plan.getModel().entities().get(existsBlock.entity());
        if (subEntity == null || subEntity.tables() == null || subEntity.tables().primary() == null) {
            return null;
        }
        String subTable = subEntity.tables().primary();
        String subAlias = "ex" + plan.getParamIdx()[0];
        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM ")
                .append(subTable).append(" ").append(subAlias);
        List<String> predicates = new ArrayList<>();
        String correlation = predicateClauseHandler.buildCorrelationPredicate(
                plan.getEntity(), plan.getAliasByTable(), subEntity, subAlias, plan.getModel());
        if (!correlation.isBlank()) {
            predicates.add(correlation);
        }
        String filterSql = predicateClauseHandler.renderFilterGroupSql(
                existsBlock.where(),
                subEntity,
                java.util.Map.of(subTable, subAlias),
                plan.getParams(),
                plan.getParamIdx(),
                plan.getModel()
        );
        if (!filterSql.isBlank()) {
            predicates.add(filterSql);
        }
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        sql.append(")");
        String expr = existsBlock.notExists() ? "NOT " + sql : sql.toString();
        return DSL.condition(expr);
    }
}

