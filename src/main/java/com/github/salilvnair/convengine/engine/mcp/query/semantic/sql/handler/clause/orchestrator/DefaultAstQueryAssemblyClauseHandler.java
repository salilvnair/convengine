package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.orchestrator;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompileWorkPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstExistsHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstFunctionHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstMetricHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstPredicateHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstSubqueryHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.core.AstWindowHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstExistsHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstFunctionHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstMetricHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstPredicateHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstSubqueryHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstWindowHandlerRegistry;
import lombok.RequiredArgsConstructor;
import org.jooq.JoinType;
import org.jooq.SQLDialect;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultAstQueryAssemblyClauseHandler implements AstClauseHandler {
    private static final Pattern TABLE_TOKEN_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.");
    private static final Pattern NUMERIC_PARAM_PATTERN = Pattern.compile(":(\\d+)\\b");

    private final ConvEngineMcpConfig mcpConfig;
    private final AstPredicateHandlerRegistry predicateHandlerRegistry;
    private final AstFunctionHandlerRegistry functionHandlerRegistry;
    private final AstWindowHandlerRegistry windowHandlerRegistry;
    private final AstExistsHandlerRegistry existsHandlerRegistry;
    private final AstSubqueryHandlerRegistry subqueryHandlerRegistry;
    private final AstMetricHandlerRegistry metricHandlerRegistry;

    @Override
    public boolean supports(CompileWorkPlan plan) {
        return plan != null && plan.getContext() != null && plan.getAst() != null;
    }

    @Override
    public void apply(CompileWorkPlan plan) {
        var ast = plan.getAst();
        var model = plan.getModel();
        var entity = plan.getEntity();
        var joinPath = plan.getJoinPath();

        String primary = entity.tables() == null ? null : entity.tables().primary();
        String baseTable = joinPath != null && joinPath.baseTable() != null && !joinPath.baseTable().isBlank()
                ? joinPath.baseTable()
                : primary;
        if (baseTable == null || baseTable.isBlank()) {
            throw new IllegalStateException("Base table missing for semantic SQL compile.");
        }

        Set<String> requiredTables = requiredTablesFromAst(plan, entity, model);
        if (!requiredTables.isEmpty() && !requiredTables.contains(baseTable)) {
            if (primary != null && requiredTables.contains(primary)) {
                baseTable = primary;
            } else {
                baseTable = requiredTables.iterator().next();
            }
        }

        List<SchemaEdge> effectiveEdges = buildEffectiveEdges(baseTable, requiredTables, joinPath == null ? null : joinPath.edges(), model);
        Map<String, String> aliasByTable = buildAliases(baseTable, effectiveEdges);

        var dsl = DSL.using(resolveDialect());
        var query = dsl.selectQuery();

        plan.setBaseTable(baseTable);
        plan.setEffectiveEdges(effectiveEdges);
        plan.setAliasByTable(aliasByTable);
        plan.setQuery(query);
        if (plan.getParams() == null) {
            plan.setParams(new LinkedHashMap<>());
        }
        if (plan.getParamIdx() == null) {
            plan.setParamIdx(new int[]{1});
        }

        List<SelectFieldOrAsterisk> selectExpr = resolveBaseSelectExpressions(entity, aliasByTable, ast.projections());
        if (selectExpr.isEmpty()) {
            throw new IllegalStateException("No valid select expressions could be generated from AST.");
        }

        if (ast.distinct()) {
            query.setDistinct(true);
        }
        query.addSelect(selectExpr);
        query.addFrom(tableRef(baseTable, aliasByTable.get(baseTable)));

        for (SchemaEdge edge : effectiveEdges) {
            String leftAlias = aliasByTable.get(edge.leftTable());
            String rightAlias = aliasByTable.get(edge.rightTable());
            if (leftAlias == null || rightAlias == null) {
                continue;
            }
            JoinType joinType = resolveJoinType(edge.joinType());
            Table<?> rightTable = tableRef(edge.rightTable(), rightAlias);
            var on = DSL.field(DSL.name(leftAlias, edge.leftColumn()))
                    .eq(DSL.field(DSL.name(rightAlias, edge.rightColumn())));
            query.addJoin(rightTable, joinType, on);
        }

        applyHandlers(predicateHandlerRegistry.handlers(), plan);
        applyHandlers(existsHandlerRegistry.handlers(), plan);
        applyHandlers(subqueryHandlerRegistry.handlers(), plan);
        applyHandlers(functionHandlerRegistry.handlers(), plan);
        applyHandlers(windowHandlerRegistry.handlers(), plan);
        applyHandlers(metricHandlerRegistry.handlers(), plan);

        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        int limit = ast.limit() <= 0 ? cfg.getDefaultLimit() : ast.limit();
        limit = Math.min(limit, cfg.getMaxLimit());
        query.addLimit(limit);
        plan.getParams().put("__limit", limit);
        if (ast.offset() > 0) {
            query.addOffset(ast.offset());
            plan.getParams().put("__offset", ast.offset());
        }

        String sql = query.getSQL(ParamType.NAMED);
        sql = normalizePaginationSql(sql, plan.getParams().containsKey("__offset"));

        Map<String, Object> alignedParams = alignSqlParams(sql, plan.getParams());
        plan.setCompiledSql(new CompiledSql(sql, Map.copyOf(alignedParams)));
    }

    private String normalizePaginationSql(String sql, boolean hasOffset) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }

        String out = sql;
        // LIMIT styles
        out = out.replaceAll("(?i)\\blimit\\s+(\\d+|:\\w+|\\?)\\b", "LIMIT :__limit");
        // ANSI fetch styles from jOOQ/dialects
        out = out.replaceAll("(?i)\\bfetch\\s+next\\s+(\\d+|:\\w+|\\?)\\s+rows\\s+only\\b", "LIMIT :__limit");
        out = out.replaceAll("(?i)\\bfetch\\s+first\\s+(\\d+|:\\w+|\\?)\\s+rows\\s+only\\b", "LIMIT :__limit");

        if (hasOffset) {
            out = out.replaceAll("(?i)\\boffset\\s+(\\d+|:\\w+|\\?)\\b", "OFFSET :__offset");
        }
        return out;
    }

    private Map<String, Object> alignSqlParams(String sql, Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>(params == null ? Map.of() : params);
        if (sql == null || sql.isBlank()) {
            return out;
        }

        Matcher matcher = NUMERIC_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            String numericKey = matcher.group(1);
            if (out.containsKey(numericKey)) {
                continue;
            }

            Object value = out.get("p" + numericKey);
            if (value == null) {
                value = resolvePositionalValue(out, Integer.parseInt(numericKey));
            }
            if (value != null) {
                out.put(numericKey, value);
            }
        }
        return out;
    }

    private Object resolvePositionalValue(Map<String, Object> params, int position) {
        if (position <= 0 || params == null || params.isEmpty()) {
            return null;
        }

        List<String> pKeys = params.keySet().stream()
                .filter(k -> k != null && k.matches("p\\d+"))
                .sorted(Comparator.comparingInt(k -> Integer.parseInt(k.substring(1))))
                .toList();

        if (position <= pKeys.size()) {
            return params.get(pKeys.get(position - 1));
        }
        int remaining = position - pKeys.size();
        if (remaining == 1 && params.containsKey("__limit")) {
            return params.get("__limit");
        }
        if (remaining == 2 && params.containsKey("__offset")) {
            return params.get("__offset");
        }
        return null;
    }

    private <T> void applyHandlers(List<T> handlers, CompileWorkPlan plan) {
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (T handler : handlers) {
            if (handler instanceof AstPredicateHandler h && h.supports(plan)) {
                h.apply(plan);
            }
            if (handler instanceof AstExistsHandler h && h.supports(plan)) {
                h.apply(plan);
            }
            if (handler instanceof AstSubqueryHandler h && h.supports(plan)) {
                h.apply(plan);
            }
            if (handler instanceof AstFunctionHandler h && h.supports(plan)) {
                h.apply(plan);
            }
            if (handler instanceof AstWindowHandler h && h.supports(plan)) {
                h.apply(plan);
            }
            if (handler instanceof AstMetricHandler h && h.supports(plan)) {
                h.apply(plan);
            }
        }
    }

    private List<SelectFieldOrAsterisk> resolveBaseSelectExpressions(SemanticEntity entity,
                                                                      Map<String, String> aliasByTable,
                                                                      List<CanonicalProjection> projections) {
        List<SelectFieldOrAsterisk> selectExpr = new ArrayList<>();
        List<CanonicalProjection> effective = projections == null || projections.isEmpty() ? defaultProjections(entity) : projections;
        for (CanonicalProjection projection : effective) {
            if (projection == null || projection.field() == null || projection.field().isBlank()) {
                continue;
            }
            SemanticField semanticField = entity.fields().get(projection.field());
            if (semanticField == null || semanticField.column() == null || semanticField.column().isBlank()) {
                continue;
            }
            var field = columnField(semanticField.column(), aliasByTable);
            String alias = projection.alias() == null || projection.alias().isBlank() ? projection.field() : projection.alias();
            selectExpr.add(field.as(alias));
        }
        return selectExpr;
    }

    private List<CanonicalProjection> defaultProjections(SemanticEntity entity) {
        if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return List.of();
        }
        List<CanonicalProjection> out = new ArrayList<>();
        for (String name : entity.fields().keySet()) {
            out.add(new CanonicalProjection(name, null));
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private Set<String> requiredTablesFromAst(CompileWorkPlan plan, SemanticEntity entity, SemanticModel model) {
        Set<String> tables = new LinkedHashSet<>();
        for (CanonicalProjection projection : plan.getAst().projections()) {
            addFieldTable(tables, entity.fields().get(projection.field()));
        }
        collectFieldTablesFromGroup(tables, plan.getAst().where(), entity);
        for (String group : plan.getAst().groupBy()) {
            addFieldTable(tables, entity.fields().get(group));
        }
        collectFieldTablesFromGroup(tables, plan.getAst().having(), entity);
        for (var sort : plan.getAst().sort()) {
            if (sort == null) continue;
            addFieldTable(tables, entity.fields().get(sort.field()));
            addMetricTablesByName(tables, sort.field(), model);
        }
        if (plan.getAst().timeRange() != null && plan.getAst().timeRange().field() != null) {
            addFieldTable(tables, entity.fields().get(plan.getAst().timeRange().field()));
        }
        if (tables.isEmpty() && entity.tables() != null && entity.tables().primary() != null) {
            tables.add(entity.tables().primary());
        }
        if (plan.getAst().metrics() != null && !plan.getAst().metrics().isEmpty()) {
            for (String metricName : plan.getAst().metrics()) {
                addMetricTablesByName(tables, metricName, model);
            }
        }
        if (plan.getAst().existsBlocks() != null) {
            for (var existsBlock : plan.getAst().existsBlocks()) {
                if (existsBlock == null || existsBlock.entity() == null) continue;
                SemanticEntity e = model.entities().get(existsBlock.entity());
                if (e != null && e.tables() != null && e.tables().primary() != null) {
                    tables.add(e.tables().primary());
                    collectFieldTablesFromGroup(tables, existsBlock.where(), e);
                }
            }
        }
        if (plan.getAst().subqueryFilters() != null) {
            for (var subqueryFilter : plan.getAst().subqueryFilters()) {
                if (subqueryFilter == null || subqueryFilter.subquery() == null) continue;
                addFieldTable(tables, entity.fields().get(subqueryFilter.field()));
                SemanticEntity sqe = model.entities().get(subqueryFilter.subquery().entity());
                if (sqe != null && sqe.tables() != null && sqe.tables().primary() != null) {
                    tables.add(sqe.tables().primary());
                    addFieldTable(tables, sqe.fields().get(subqueryFilter.subquery().selectField()));
                    for (String gb : subqueryFilter.subquery().groupBy()) {
                        addFieldTable(tables, sqe.fields().get(gb));
                    }
                    collectFieldTablesFromGroup(tables, subqueryFilter.subquery().where(), sqe);
                    collectFieldTablesFromGroup(tables, subqueryFilter.subquery().having(), sqe);
                }
            }
        }
        return tables;
    }

    private void collectFieldTablesFromGroup(Set<String> tables, CanonicalFilterGroup group, SemanticEntity entity) {
        if (tables == null || group == null || entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return;
        }
        if (group.conditions() != null) {
            for (CanonicalFilter condition : group.conditions()) {
                if (condition == null || condition.field() == null || condition.field().isBlank()) {
                    continue;
                }
                addFieldTable(tables, entity.fields().get(condition.field()));
            }
        }
        if (group.groups() != null) {
            for (CanonicalFilterGroup child : group.groups()) {
                collectFieldTablesFromGroup(tables, child, entity);
            }
        }
    }

    private void addMetricTablesByName(Set<String> tables, String metricName, SemanticModel model) {
        if (tables == null || metricName == null || model == null || model.metrics() == null) {
            return;
        }
        var metric = model.metrics().get(metricName);
        if (metric == null || metric.sql() == null || metric.sql().isBlank()) {
            return;
        }
        Matcher matcher = TABLE_TOKEN_PATTERN.matcher(metric.sql());
        while (matcher.find()) {
            String table = matcher.group(1);
            if (table != null && !table.isBlank()) {
                tables.add(table);
            }
        }
    }

    private void addFieldTable(Set<String> tables, SemanticField field) {
        if (field == null || field.column() == null || field.column().isBlank()) {
            return;
        }
        String[] parts = field.column().split("\\.");
        if (parts.length == 2 && !parts[0].isBlank()) {
            tables.add(parts[0]);
        }
    }

    private List<SchemaEdge> buildEffectiveEdges(String baseTable,
                                                 Set<String> requiredTables,
                                                 List<SchemaEdge> joinEdges,
                                                 SemanticModel model) {
        List<SchemaEdge> existing = joinEdges == null ? new ArrayList<>() : new ArrayList<>(joinEdges);
        if (requiredTables == null || requiredTables.isEmpty()) {
            return existing;
        }
        Set<String> connected = connectedTables(baseTable, existing);
        connected.add(baseTable);
        List<SchemaEdge> out = new ArrayList<>(existing);
        for (String target : requiredTables) {
            if (target == null || target.isBlank() || connected.contains(target)) {
                continue;
            }
            List<SchemaEdge> path = shortestPath(model, connected, target);
            if (path.isEmpty()) {
                continue;
            }
            for (SchemaEdge edge : path) {
                if (!containsEdge(out, edge)) {
                    out.add(edge);
                }
                connected.add(edge.leftTable());
                connected.add(edge.rightTable());
            }
        }
        return out;
    }

    private Set<String> connectedTables(String base, List<SchemaEdge> edges) {
        Set<String> connected = new LinkedHashSet<>();
        if (base != null) connected.add(base);
        boolean changed;
        do {
            changed = false;
            for (SchemaEdge edge : edges) {
                if (edge == null) continue;
                boolean left = connected.contains(edge.leftTable());
                boolean right = connected.contains(edge.rightTable());
                if (left && !right) changed = connected.add(edge.rightTable()) || changed;
                else if (right && !left) changed = connected.add(edge.leftTable()) || changed;
            }
        } while (changed);
        return connected;
    }

    private boolean containsEdge(List<SchemaEdge> edges, SchemaEdge candidate) {
        for (SchemaEdge e : edges) {
            if (e == null || candidate == null) continue;
            if (Objects.equals(e.leftTable(), candidate.leftTable())
                    && Objects.equals(e.leftColumn(), candidate.leftColumn())
                    && Objects.equals(e.rightTable(), candidate.rightTable())
                    && Objects.equals(e.rightColumn(), candidate.rightColumn())) {
                return true;
            }
        }
        return false;
    }

    private List<SchemaEdge> shortestPath(SemanticModel model, Set<String> starts, String target) {
        List<SchemaEdge> graphEdges = semanticGraphEdges(model);
        if (graphEdges.isEmpty()) {
            return List.of();
        }
        Set<String> startSet = starts == null || starts.isEmpty() ? Set.of(target) : starts;
        Queue<String> queue = new ArrayDeque<>(startSet);
        Map<String, String> prev = new LinkedHashMap<>();
        Map<String, SchemaEdge> prevEdge = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(startSet);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (Objects.equals(cur, target)) {
                break;
            }
            for (SchemaEdge edge : graphEdges) {
                if (edge == null) continue;
                String a = edge.leftTable();
                String b = edge.rightTable();
                if (a == null || b == null) {
                    continue;
                }
                if (a.equals(cur) && !visited.contains(b)) {
                    visited.add(b);
                    queue.add(b);
                    prev.put(b, a);
                    prevEdge.put(b, edge);
                }
                if (b.equals(cur) && !visited.contains(a)) {
                    visited.add(a);
                    queue.add(a);
                    prev.put(a, b);
                    prevEdge.put(a, new SchemaEdge(
                            b,
                            edge.rightColumn(),
                            a,
                            edge.leftColumn(),
                            edge.source(),
                            edge.joinType()
                    ));
                }
            }
        }

        if (!visited.contains(target) || startSet.contains(target)) {
            return List.of();
        }

        List<SchemaEdge> path = new ArrayList<>();
        String node = target;
        while (!startSet.contains(node)) {
            SchemaEdge edge = prevEdge.get(node);
            if (edge == null) {
                return List.of();
            }
            path.add(0, edge);
            node = prev.get(node);
            if (node == null) {
                return List.of();
            }
        }
        return path;
    }

    private List<SchemaEdge> semanticGraphEdges(SemanticModel model) {
        if (model == null) {
            return List.of();
        }
        List<SchemaEdge> edges = new ArrayList<>();

        if (model.relationships() != null) {
            for (SemanticRelationship rel : model.relationships()) {
                if (rel == null || rel.from() == null || rel.to() == null) {
                    continue;
                }
                String a = rel.from().table();
                String b = rel.to().table();
                String aCol = rel.from().column();
                String bCol = rel.to().column();
                if (a == null || b == null || aCol == null || bCol == null) {
                    continue;
                }
                addGraphEdge(edges, new SchemaEdge(a, aCol, b, bCol, "relationship", "LEFT"));
            }
        }

        if (model.tables() != null) {
            for (Map.Entry<String, com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticTable> tableEntry : model.tables().entrySet()) {
                String fkTable = tableEntry.getKey();
                var table = tableEntry.getValue();
                if (fkTable == null || table == null || table.columns() == null) {
                    continue;
                }
                for (Map.Entry<String, com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticColumn> colEntry : table.columns().entrySet()) {
                    String fkColumn = colEntry.getKey();
                    var column = colEntry.getValue();
                    if (fkColumn == null || column == null || column.foreignKey() == null) {
                        continue;
                    }
                    String pkTable = column.foreignKey().table();
                    String pkColumn = column.foreignKey().column();
                    if (pkTable == null || pkColumn == null) {
                        continue;
                    }
                    addGraphEdge(edges, new SchemaEdge(pkTable, pkColumn, fkTable, fkColumn, "foreign_key", "LEFT"));
                }
            }
        }
        return edges;
    }

    private void addGraphEdge(List<SchemaEdge> edges, SchemaEdge candidate) {
        if (edges == null || candidate == null) {
            return;
        }
        for (SchemaEdge edge : edges) {
            if (edge == null) continue;
            if (Objects.equals(edge.leftTable(), candidate.leftTable())
                    && Objects.equals(edge.leftColumn(), candidate.leftColumn())
                    && Objects.equals(edge.rightTable(), candidate.rightTable())
                    && Objects.equals(edge.rightColumn(), candidate.rightColumn())) {
                return;
            }
        }
        edges.add(candidate);
    }

    private Map<String, String> buildAliases(String baseTable, List<SchemaEdge> edges) {
        Map<String, String> alias = new LinkedHashMap<>();
        alias.put(baseTable, "t0");
        int idx = 1;
        for (SchemaEdge e : edges) {
            if (!alias.containsKey(e.leftTable())) {
                alias.put(e.leftTable(), "t" + idx++);
            }
            if (!alias.containsKey(e.rightTable())) {
                alias.put(e.rightTable(), "t" + idx++);
            }
        }
        return alias;
    }

    private Table<?> tableRef(String tableName, String alias) {
        if (alias == null || alias.isBlank()) {
            return DSL.table(DSL.name(tableName));
        }
        return DSL.table(DSL.name(tableName)).as(alias);
    }

    private org.jooq.Field<Object> columnField(String tableDotColumn, Map<String, String> aliasByTable) {
        String[] parts = tableDotColumn.split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid semantic field column format: " + tableDotColumn);
        }
        String alias = aliasByTable.getOrDefault(parts[0], parts[0]);
        return DSL.field(DSL.name(alias, parts[1]));
    }

    private JoinType resolveJoinType(String joinType) {
        if (joinType == null || joinType.isBlank()) {
            return JoinType.LEFT_OUTER_JOIN;
        }
        return switch (joinType.toUpperCase()) {
            case "INNER" -> JoinType.JOIN;
            case "RIGHT" -> JoinType.RIGHT_OUTER_JOIN;
            case "FULL" -> JoinType.FULL_OUTER_JOIN;
            default -> JoinType.LEFT_OUTER_JOIN;
        };
    }

    private SQLDialect resolveDialect() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg.getSqlDialect() == null || cfg.getSqlDialect().isBlank()) {
            return SQLDialect.POSTGRES;
        }
        try {
            return SQLDialect.valueOf(cfg.getSqlDialect().trim().toUpperCase());
        } catch (Exception ex) {
            return SQLDialect.POSTGRES;
        }
    }
}
