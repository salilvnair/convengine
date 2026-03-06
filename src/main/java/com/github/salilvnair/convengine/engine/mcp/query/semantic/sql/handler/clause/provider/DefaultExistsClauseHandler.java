package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstExistsHandler;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        SemanticModel model = plan.getModel();
        SemanticEntity subEntity = model.entities().get(existsBlock.entity());
        if (subEntity == null || subEntity.tables() == null || subEntity.tables().primary() == null) {
            return null;
        }
        String subTable = subEntity.tables().primary();
        String subAlias = "ex" + plan.getParamIdx()[0];
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(subTable, subAlias);

        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM ").append(subTable).append(" ").append(subAlias);
        appendRequiredJoins(sql, existsBlock.where(), subEntity, subTable, aliases, model);

        List<String> predicates = new ArrayList<>();
        String correlation = predicateClauseHandler.buildCorrelationPredicate(
                plan.getEntity(), plan.getAliasByTable(), subEntity, subAlias, model);
        if (!correlation.isBlank()) {
            predicates.add(correlation);
        }
        String filterSql = predicateClauseHandler.renderFilterGroupSql(
                existsBlock.where(),
                subEntity,
                aliases,
                plan.getParams(),
                plan.getParamIdx(),
                model,
                fieldRef -> predicateClauseHandler.resolveEntityField(plan.getEntity(), fieldRef, plan.getAliasByTable())
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

    private void appendRequiredJoins(StringBuilder sql,
                                     CanonicalFilterGroup where,
                                     SemanticEntity entity,
                                     String baseTable,
                                     Map<String, String> aliases,
                                     SemanticModel model) {
        Set<String> requiredTables = collectRequiredTables(where, entity);
        if (requiredTables.isEmpty()) {
            return;
        }
        requiredTables.remove(baseTable);
        int[] aliasSeq = new int[]{1};
        for (String requiredTable : requiredTables) {
            List<RelationshipEdge> path = findRelationshipPath(baseTable, requiredTable, model);
            if (path.isEmpty()) {
                continue;
            }
            for (RelationshipEdge edge : path) {
                String fromAlias = aliases.computeIfAbsent(edge.fromTable(), t -> "exj" + aliasSeq[0]++);
                if (aliases.containsKey(edge.toTable())) {
                    continue;
                }
                String toAlias = "exj" + aliasSeq[0]++;
                aliases.put(edge.toTable(), toAlias);
                sql.append(" LEFT JOIN ").append(edge.toTable()).append(" ").append(toAlias)
                        .append(" ON ").append(fromAlias).append(".").append(edge.fromColumn())
                        .append(" = ").append(toAlias).append(".").append(edge.toColumn());
            }
        }
    }

    private Set<String> collectRequiredTables(CanonicalFilterGroup where, SemanticEntity entity) {
        Set<String> tables = new LinkedHashSet<>();
        collectRequiredTables(where, entity, tables);
        return tables;
    }

    private void collectRequiredTables(CanonicalFilterGroup where, SemanticEntity entity, Set<String> tables) {
        if (where == null || entity == null || entity.fields() == null) {
            return;
        }
        if (where.conditions() != null) {
            for (CanonicalFilter filter : where.conditions()) {
                if (filter == null || filter.field() == null || filter.field().isBlank()) {
                    continue;
                }
                SemanticField field = entity.fields().get(filter.field());
                if (field == null || field.column() == null || field.column().isBlank()) {
                    continue;
                }
                String[] parts = field.column().split("\\.");
                if (parts.length == 2 && !parts[0].isBlank()) {
                    tables.add(parts[0]);
                }
            }
        }
        if (where.groups() != null) {
            for (CanonicalFilterGroup child : where.groups()) {
                collectRequiredTables(child, entity, tables);
            }
        }
    }

    private List<RelationshipEdge> findRelationshipPath(String fromTable, String toTable, SemanticModel model) {
        if (fromTable == null || toTable == null || model == null || model.relationships() == null) {
            return List.of();
        }
        if (fromTable.equals(toTable)) {
            return List.of();
        }
        Map<String, List<RelationshipEdge>> graph = new LinkedHashMap<>();
        for (SemanticRelationship relationship : model.relationships()) {
            if (relationship == null || relationship.from() == null || relationship.to() == null) {
                continue;
            }
            String leftTable = relationship.from().table();
            String leftColumn = relationship.from().column();
            String rightTable = relationship.to().table();
            String rightColumn = relationship.to().column();
            if (leftTable == null || leftColumn == null || rightTable == null || rightColumn == null) {
                continue;
            }
            graph.computeIfAbsent(leftTable, k -> new ArrayList<>())
                    .add(new RelationshipEdge(leftTable, leftColumn, rightTable, rightColumn));
            graph.computeIfAbsent(rightTable, k -> new ArrayList<>())
                    .add(new RelationshipEdge(rightTable, rightColumn, leftTable, leftColumn));
        }

        Deque<String> queue = new ArrayDeque<>();
        Map<String, RelationshipEdge> prev = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(fromTable);
        visited.add(fromTable);

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (current.equals(toTable)) {
                break;
            }
            for (RelationshipEdge edge : graph.getOrDefault(current, List.of())) {
                if (visited.add(edge.toTable())) {
                    prev.put(edge.toTable(), edge);
                    queue.add(edge.toTable());
                }
            }
        }

        if (!visited.contains(toTable)) {
            return List.of();
        }

        List<RelationshipEdge> path = new ArrayList<>();
        String cursor = toTable;
        while (!cursor.equals(fromTable)) {
            RelationshipEdge edge = prev.get(cursor);
            if (edge == null) {
                return List.of();
            }
            path.add(0, edge);
            cursor = edge.fromTable();
        }
        return path;
    }

    private record RelationshipEdge(
            String fromTable,
            String fromColumn,
            String toTable,
            String toColumn
    ) {
    }
}
