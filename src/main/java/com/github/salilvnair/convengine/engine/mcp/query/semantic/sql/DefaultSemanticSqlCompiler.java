package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.SemanticQueryAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JoinType;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectQuery;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultSemanticSqlCompiler implements SemanticSqlCompiler {

    private final SemanticModelRegistry modelRegistry;
    private final ConvEngineMcpConfig mcpConfig;

    @Override
    public boolean supports(SemanticQueryContext context) {
        String compiler = mcpConfig.getDb() == null
                || mcpConfig.getDb().getSemantic() == null
                || mcpConfig.getDb().getSemantic().getSql() == null
                ? "default"
                : mcpConfig.getDb().getSemantic().getSql().getCompiler();
        return compiler == null || compiler.isBlank() || "default".equalsIgnoreCase(compiler);
    }

    @Override
    public CompiledSql compile(SemanticQueryContext context) {
        if (context == null || context.astGeneration() == null || context.astGeneration().ast() == null) {
            throw new IllegalStateException("AST missing for semantic SQL compile.");
        }
        SemanticQueryAst ast = context.astGeneration().ast();
        SemanticModel model = modelRegistry.getModel();
        SemanticEntity entity = model.entities().get(ast.entity());
        if (entity == null) {
            throw new IllegalStateException("Unknown entity in AST: " + ast.entity());
        }
        JoinPathPlan joinPath = context.joinPath();
        String primaryName = entity.tables() == null ? null : entity.tables().primary();
        String baseTable = joinPath != null && joinPath.baseTable() != null && !joinPath.baseTable().isBlank()
                ? joinPath.baseTable()
                : primaryName;
        if (baseTable == null || baseTable.isBlank()) {
            throw new IllegalStateException("Base table missing for semantic SQL compile.");
        }
        Set<String> requiredTables = requiredTablesFromAst(ast, entity);
        if (!requiredTables.isEmpty() && !requiredTables.contains(baseTable)) {
            String primary = primaryName;
            if (primary != null && requiredTables.contains(primary)) {
                baseTable = primary;
            } else {
                baseTable = requiredTables.iterator().next();
            }
        }
        List<SchemaEdge> effectiveEdges = buildEffectiveEdges(baseTable, requiredTables, joinPath, model);

        Map<String, String> aliasByTable = buildAliases(baseTable, effectiveEdges);
        Map<String, Object> params = new LinkedHashMap<>();
        int[] paramIdx = new int[] {1};
        var dsl = DSL.using(resolveDialect());
        SelectQuery<?> query = dsl.selectQuery();

        List<String> selectFields = ast.select().isEmpty() ? defaultSelectFields(entity) : ast.select();
        List<SelectFieldOrAsterisk> selectExpr = new ArrayList<>();
        for (String fieldName : selectFields) {
            SemanticField field = entity.fields().get(fieldName);
            if (field == null || field.column() == null || field.column().isBlank()) {
                continue;
            }
            Field<Object> col = columnField(field.column(), aliasByTable);
            selectExpr.add(col.as(fieldName));
        }
        if (selectExpr.isEmpty()) {
            throw new IllegalStateException("No valid select expressions could be generated from AST.");
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
            Condition on = DSL.field(DSL.name(leftAlias, edge.leftColumn()))
                    .eq(DSL.field(DSL.name(rightAlias, edge.rightColumn())));
            query.addJoin(rightTable, joinType, on);
        }

        List<Condition> where = new ArrayList<>();
        for (AstFilter filter : ast.filters()) {
            if (filter == null || filter.field() == null) {
                continue;
            }
            SemanticField field = entity.fields().get(filter.field());
            if (field == null || field.column() == null) {
                continue;
            }
            String op = filter.op() == null ? "=" : filter.op().toUpperCase(Locale.ROOT);
            Field<Object> colField = columnField(field.column(), aliasByTable);
            switch (op) {
                case "IS_NULL" -> where.add(colField.isNull());
                case "IS_NOT_NULL" -> where.add(colField.isNotNull());
                case "IN" -> {
                    List<?> values = filter.value() instanceof List<?> l ? l : List.of(filter.value());
                    if (values.isEmpty()) {
                        continue;
                    }
                    List<Field<?>> inParams = new ArrayList<>();
                    for (Object v : values) {
                        String p = "p" + paramIdx[0]++;
                        params.put(p, v);
                        inParams.add(DSL.param(p, v));
                    }
                    where.add(colField.in(inParams));
                }
                case "BETWEEN" -> {
                    if (!(filter.value() instanceof List<?> l) || l.size() < 2) {
                        continue;
                    }
                    String p1 = "p" + paramIdx[0]++;
                    String p2 = "p" + paramIdx[0]++;
                    params.put(p1, l.get(0));
                    params.put(p2, l.get(1));
                    Param<Object> a = DSL.param(p1, l.get(0));
                    Param<Object> b = DSL.param(p2, l.get(1));
                    where.add(DSL.condition("{0} BETWEEN {1} AND {2}", colField, a, b));
                }
                default -> {
                    String p = "p" + paramIdx[0]++;
                    params.put(p, filter.value());
                    Param<Object> valueParam = DSL.param(p, filter.value());
                    where.add(DSL.condition("{0} " + op + " {1}", colField, valueParam));
                }
            }
        }

        if (ast.timeRange() != null && ast.timeRange().field() != null) {
            SemanticField tField = entity.fields().get(ast.timeRange().field());
            if (tField != null && tField.column() != null) {
                Field<Object> colField = columnField(tField.column(), aliasByTable);
                if (ast.timeRange().from() != null && !ast.timeRange().from().isBlank()) {
                    String p = "p" + paramIdx[0]++;
                    Object value = resolveTimeValue(ast.timeRange().from());
                    params.put(p, value);
                    where.add(DSL.condition("{0} >= {1}", colField, DSL.param(p, value)));
                }
                if (ast.timeRange().to() != null && !ast.timeRange().to().isBlank()) {
                    String p = "p" + paramIdx[0]++;
                    Object value = resolveTimeValue(ast.timeRange().to());
                    params.put(p, value);
                    where.add(DSL.condition("{0} <= {1}", colField, DSL.param(p, value)));
                }
            }
        }

        if (!where.isEmpty()) {
            query.addConditions(where);
        }

        if (!ast.groupBy().isEmpty()) {
            List<Field<?>> gb = new ArrayList<>();
            for (String fieldName : ast.groupBy()) {
                SemanticField field = entity.fields().get(fieldName);
                if (field != null && field.column() != null) {
                    gb.add(columnField(field.column(), aliasByTable));
                }
            }
            if (!gb.isEmpty()) {
                query.addGroupBy(gb);
            }
        }

        if (!ast.sort().isEmpty()) {
            List<SortField<?>> sorts = new ArrayList<>();
            for (AstSort sort : ast.sort()) {
                if (sort == null || sort.field() == null) {
                    continue;
                }
                SemanticField field = entity.fields().get(sort.field());
                if (field == null || field.column() == null) {
                    continue;
                }
                SortOrder order = "DESC".equalsIgnoreCase(sort.direction()) ? SortOrder.DESC : SortOrder.ASC;
                sorts.add(columnField(field.column(), aliasByTable).sort(order));
            }
            if (!sorts.isEmpty()) {
                query.addOrderBy(sorts);
            }
        }

        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        int limit = ast.limit() == null || ast.limit() <= 0 ? cfg.getDefaultLimit() : ast.limit();
        limit = Math.min(limit, cfg.getMaxLimit());
        query.addLimit(limit);
        params.put("__limit", limit);

        String sql = query.getSQL(ParamType.NAMED);
        sql = sql.replaceAll("(?i)\\blimit\\s+\\d+\\b", "LIMIT :__limit");
        return new CompiledSql(sql, Map.copyOf(params));
    }

    private Set<String> requiredTablesFromAst(SemanticQueryAst ast, SemanticEntity entity) {
        Set<String> tables = new LinkedHashSet<>();
        if (entity == null || entity.fields() == null) {
            return tables;
        }
        List<String> selectFields = ast.select().isEmpty() ? defaultSelectFields(entity) : ast.select();
        for (String fieldName : selectFields) {
            addFieldTable(tables, entity.fields().get(fieldName));
        }
        for (AstFilter filter : ast.filters()) {
            if (filter == null) {
                continue;
            }
            addFieldTable(tables, entity.fields().get(filter.field()));
        }
        for (String group : ast.groupBy()) {
            addFieldTable(tables, entity.fields().get(group));
        }
        for (AstSort sort : ast.sort()) {
            if (sort == null) {
                continue;
            }
            addFieldTable(tables, entity.fields().get(sort.field()));
        }
        if (ast.timeRange() != null && ast.timeRange().field() != null) {
            addFieldTable(tables, entity.fields().get(ast.timeRange().field()));
        }
        if (tables.isEmpty() && entity.tables() != null && entity.tables().primary() != null) {
            tables.add(entity.tables().primary());
        }
        return tables;
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
                                                 JoinPathPlan joinPath,
                                                 SemanticModel model) {
        List<SchemaEdge> existing = joinPath == null || joinPath.edges() == null
                ? new ArrayList<>()
                : new ArrayList<>(joinPath.edges());
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
        if (base != null) {
            connected.add(base);
        }
        boolean changed;
        do {
            changed = false;
            for (SchemaEdge edge : edges) {
                if (edge == null) {
                    continue;
                }
                boolean left = connected.contains(edge.leftTable());
                boolean right = connected.contains(edge.rightTable());
                if (left && !right) {
                    changed = connected.add(edge.rightTable()) || changed;
                } else if (right && !left) {
                    changed = connected.add(edge.leftTable()) || changed;
                }
            }
        } while (changed);
        return connected;
    }

    private boolean containsEdge(List<SchemaEdge> edges, SchemaEdge candidate) {
        for (SchemaEdge e : edges) {
            if (e == null || candidate == null) {
                continue;
            }
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
        if (model == null || model.relationships() == null || model.relationships().isEmpty()) {
            return List.of();
        }
        List<SchemaEdge> relEdges = relationshipEdges(model.relationships());
        if (relEdges.isEmpty()) {
            return List.of();
        }
        Queue<PathNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String s : starts) {
            if (s == null || s.isBlank()) {
                continue;
            }
            queue.add(new PathNode(s, List.of()));
            visited.add(s);
        }
        while (!queue.isEmpty()) {
            PathNode node = queue.poll();
            if (target.equals(node.table)) {
                return node.path;
            }
            for (SchemaEdge edge : relEdges) {
                SchemaEdge next = traverse(edge, node.table);
                if (next == null) {
                    continue;
                }
                String nextTable = next.rightTable();
                if (!visited.add(nextTable)) {
                    continue;
                }
                List<SchemaEdge> nextPath = new ArrayList<>(node.path);
                nextPath.add(next);
                queue.add(new PathNode(nextTable, nextPath));
            }
        }
        return List.of();
    }

    private List<SchemaEdge> relationshipEdges(List<SemanticRelationship> relationships) {
        List<SchemaEdge> out = new ArrayList<>();
        for (SemanticRelationship rel : relationships) {
            if (rel == null || rel.from() == null || rel.to() == null) {
                continue;
            }
            if (rel.from().table() == null || rel.from().column() == null || rel.to().table() == null || rel.to().column() == null) {
                continue;
            }
            out.add(new SchemaEdge(
                    rel.from().table(),
                    rel.from().column(),
                    rel.to().table(),
                    rel.to().column(),
                    "RELATIONSHIP",
                    "JOIN"
            ));
        }
        return out;
    }

    private SchemaEdge traverse(SchemaEdge edge, String currentTable) {
        if (edge == null || currentTable == null) {
            return null;
        }
        if (currentTable.equals(edge.leftTable())) {
            return edge;
        }
        if (currentTable.equals(edge.rightTable())) {
            return new SchemaEdge(
                    edge.rightTable(),
                    edge.rightColumn(),
                    edge.leftTable(),
                    edge.leftColumn(),
                    edge.source(),
                    edge.joinType()
            );
        }
        return null;
    }

    private record PathNode(String table, List<SchemaEdge> path) {}

    private String resolveTimeValue(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now();
        if ("now".equals(v)) {
            return now.toString();
        }
        if (v.startsWith("now-") && v.endsWith("h")) {
            int hours = parseInt(v.substring(4, v.length() - 1), 0);
            return now.minusHours(Math.max(hours, 0)).toString();
        }
        if (v.startsWith("now-") && v.endsWith("d")) {
            int days = parseInt(v.substring(4, v.length() - 1), 0);
            return now.minusDays(Math.max(days, 0)).toString();
        }
        return value;
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private List<String> defaultSelectFields(SemanticEntity entity) {
        if (entity.fields() == null || entity.fields().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String name : entity.fields().keySet()) {
            out.add(name);
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private Field<Object> columnField(String qualifiedColumn, Map<String, String> aliasByTable) {
        String[] parts = qualifiedColumn.split("\\.");
        if (parts.length != 2) {
            return DSL.field(DSL.name(qualifiedColumn));
        }
        String table = parts[0];
        String col = parts[1];
        String alias = aliasByTable.getOrDefault(table, table);
        return DSL.field(DSL.name(alias, col));
    }

    private Table<?> tableRef(String table, String alias) {
        if (alias == null || alias.isBlank()) {
            return DSL.table(DSL.name(table));
        }
        return DSL.table(DSL.name(table)).as(alias);
    }

    private JoinType resolveJoinType(String joinType) {
        if (joinType == null || joinType.isBlank()) {
            return JoinType.JOIN;
        }
        String normalized = joinType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LEFT", "LEFT JOIN", "LEFT_OUTER", "LEFT OUTER", "LEFT OUTER JOIN" -> JoinType.LEFT_OUTER_JOIN;
            case "RIGHT", "RIGHT JOIN", "RIGHT_OUTER", "RIGHT OUTER", "RIGHT OUTER JOIN" -> JoinType.RIGHT_OUTER_JOIN;
            case "FULL", "FULL JOIN", "FULL_OUTER", "FULL OUTER", "FULL OUTER JOIN" -> JoinType.FULL_OUTER_JOIN;
            case "CROSS", "CROSS JOIN" -> JoinType.CROSS_JOIN;
            case "INNER", "INNER JOIN", "JOIN" -> JoinType.JOIN;
            default -> JoinType.JOIN;
        };
    }

    private SQLDialect resolveDialect() {
        String dialect = mcpConfig.getDb() == null
                || mcpConfig.getDb().getSemantic() == null
                || mcpConfig.getDb().getSemantic().getSqlDialect() == null
                ? "postgres"
                : mcpConfig.getDb().getSemantic().getSqlDialect();
        String normalized = dialect.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL" -> SQLDialect.POSTGRES;
            case "MYSQL" -> SQLDialect.MYSQL;
            case "MARIADB" -> SQLDialect.MARIADB;
            case "SQLITE" -> SQLDialect.SQLITE;
            case "H2" -> SQLDialect.H2;
            default -> SQLDialect.POSTGRES;
        };
    }

    private Map<String, String> buildAliases(String baseTable, List<SchemaEdge> edges) {
        Set<String> tables = new LinkedHashSet<>();
        tables.add(baseTable);
        for (SchemaEdge edge : edges) {
            tables.add(edge.leftTable());
            tables.add(edge.rightTable());
        }
        Map<String, String> aliasByTable = new LinkedHashMap<>();
        int idx = 0;
        for (String table : tables) {
            aliasByTable.put(table, "t" + idx++);
        }
        return aliasByTable;
    }
}
