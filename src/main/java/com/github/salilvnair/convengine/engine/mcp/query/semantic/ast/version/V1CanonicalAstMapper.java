package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubquerySpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class V1CanonicalAstMapper {

    public CanonicalAst from(SemanticQueryAstV1 ast) {
        if (ast == null) {
            return new CanonicalAst("v1", "", List.of(), null, null, List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), 100, 0, false, List.of());
        }
        List<CanonicalProjection> projections = toProjections(ast);
        CanonicalFilterGroup where = toWhere(ast.where(), ast.filters());
        CanonicalFilterGroup having = toGroup(ast.having());
        List<CanonicalExistsBlock> exists = toExists(ast.existsBlocks());
        List<CanonicalSubqueryFilter> subqueryFilters = toSubqueryFilters(ast.subqueryFilters());
        List<CanonicalWindowSpec> windows = toWindows(ast.windows());
        List<CanonicalSort> sort = ast.sort() == null ? List.of() : ast.sort().stream()
                .filter(s -> s != null && s.field() != null && !s.field().isBlank())
                .map(s -> new CanonicalSort(s.field(), safeDirection(s), safeNulls(s)))
                .toList();
        return new CanonicalAst(
                ast.astVersion(),
                ast.entity(),
                projections,
                where,
                ast.timeRange(),
                exists,
                subqueryFilters,
                ast.groupBy(),
                ast.metrics(),
                having,
                windows,
                sort,
                ast.limit() == null ? 100 : ast.limit(),
                ast.offset() == null ? 0 : ast.offset(),
                ast.distinct() != null && ast.distinct(),
                ast.joinHints()
        );
    }

    private List<CanonicalProjection> toProjections(SemanticQueryAstV1 ast) {
        List<CanonicalProjection> out = new ArrayList<>();
        if (ast.projections() != null && !ast.projections().isEmpty()) {
            for (AstProjection projection : ast.projections()) {
                if (projection == null || projection.field() == null || projection.field().isBlank()) {
                    continue;
                }
                out.add(new CanonicalProjection(projection.field(), projection.alias()));
            }
        }
        if (out.isEmpty() && ast.select() != null) {
            for (String field : ast.select()) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                out.add(new CanonicalProjection(field, null));
            }
        }
        return out;
    }

    private CanonicalFilterGroup toWhere(AstFilterGroup where, List<AstFilter> flatFilters) {
        if (where != null) {
            return toGroup(where);
        }
        if (flatFilters == null || flatFilters.isEmpty()) {
            return new CanonicalFilterGroup(null, List.of(), List.of());
        }
        List<CanonicalFilter> conds = flatFilters.stream()
                .filter(f -> f != null && f.field() != null && !f.field().isBlank())
                .map(this::toFilter)
                .toList();
        return new CanonicalFilterGroup(null, conds, List.of());
    }

    private CanonicalFilterGroup toGroup(AstFilterGroup group) {
        if (group == null) {
            return new CanonicalFilterGroup(null, List.of(), List.of());
        }
        List<CanonicalFilter> conditions = group.conditions() == null ? List.of() : group.conditions().stream()
                .filter(f -> f != null && f.field() != null && !f.field().isBlank())
                .map(this::toFilter)
                .toList();
        List<CanonicalFilterGroup> groups = group.groups() == null ? List.of() : group.groups().stream()
                .map(this::toGroup)
                .toList();
        return new CanonicalFilterGroup(group.logicalOperator(), conditions, groups);
    }

    private CanonicalFilter toFilter(AstFilter filter) {
        AstOperator operator = parseOperator(filter == null ? null : filter.op(), filter == null ? null : filter.field());
        return new CanonicalFilter(filter.field(), operator, filter.value());
    }

    private List<CanonicalExistsBlock> toExists(List<AstExistsBlock> existsBlocks) {
        if (existsBlocks == null || existsBlocks.isEmpty()) {
            return List.of();
        }
        return existsBlocks.stream()
                .filter(e -> e != null && e.entity() != null && !e.entity().isBlank())
                .map(e -> new CanonicalExistsBlock(
                        e.entity(),
                        toGroup(e.where()),
                        e.notExists() != null && e.notExists()
                ))
                .toList();
    }

    private List<CanonicalSubqueryFilter> toSubqueryFilters(List<AstSubqueryFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        return filters.stream()
                .filter(f -> f != null && f.field() != null && !f.field().isBlank() && f.subquery() != null)
                .map(f -> {
                    AstOperator operator = parseOperator(f.op(), f.field());
                    return new CanonicalSubqueryFilter(
                            f.field(),
                            operator,
                            new CanonicalSubquerySpec(
                                    f.subquery().entity(),
                                    f.subquery().selectField(),
                                    toGroup(f.subquery().where()),
                                    f.subquery().groupBy(),
                                    toGroup(f.subquery().having()),
                                    f.subquery().limit() == null ? 1 : f.subquery().limit()
                            )
                    );
                })
                .toList();
    }

    private AstOperator parseOperator(String op, String field) {
        try {
            return new AstFilter(field, op, null).operatorEnum();
        } catch (Exception ex) {
            throw new IllegalStateException("Unsupported AST operator: " + op + " for field: " + field, ex);
        }
    }

    private List<CanonicalWindowSpec> toWindows(List<AstWindowSpec> windows) {
        if (windows == null || windows.isEmpty()) {
            return List.of();
        }
        return windows.stream()
                .filter(w -> w != null && w.function() != null && !w.function().isBlank())
                .map(w -> new CanonicalWindowSpec(
                        w.name(),
                        w.function(),
                        w.partitionBy(),
                        w.orderBy() == null ? List.of() : w.orderBy().stream()
                                .filter(s -> s != null && s.field() != null && !s.field().isBlank())
                                .map(s -> new CanonicalSort(s.field(), safeDirection(s), safeNulls(s)))
                                .toList()
                ))
                .toList();
    }

    private com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SortDirection safeDirection(AstSort sort) {
        try {
            return sort.directionEnum();
        } catch (Exception ex) {
            return com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SortDirection.ASC;
        }
    }

    private com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.NullsOrder safeNulls(AstSort sort) {
        try {
            return sort.nullsEnum();
        } catch (Exception ex) {
            return null;
        }
    }
}
