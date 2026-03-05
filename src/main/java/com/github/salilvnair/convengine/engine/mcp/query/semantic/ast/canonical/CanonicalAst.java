package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstJoinHint;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstTimeRange;

import java.util.List;

public record CanonicalAst(
        String astVersion,
        String entity,
        List<CanonicalProjection> projections,
        CanonicalFilterGroup where,
        AstTimeRange timeRange,
        java.util.List<CanonicalExistsBlock> existsBlocks,
        java.util.List<CanonicalSubqueryFilter> subqueryFilters,
        List<String> groupBy,
        List<String> metrics,
        CanonicalFilterGroup having,
        java.util.List<CanonicalWindowSpec> windows,
        List<CanonicalSort> sort,
        int limit,
        int offset,
        boolean distinct,
        List<AstJoinHint> joinHints
) {
    public CanonicalAst {
        astVersion = astVersion == null || astVersion.isBlank() ? "v1" : astVersion;
        projections = projections == null ? List.of() : List.copyOf(projections);
        existsBlocks = existsBlocks == null ? List.of() : List.copyOf(existsBlocks);
        subqueryFilters = subqueryFilters == null ? List.of() : List.copyOf(subqueryFilters);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        windows = windows == null ? List.of() : List.copyOf(windows);
        sort = sort == null ? List.of() : List.copyOf(sort);
        joinHints = joinHints == null ? List.of() : List.copyOf(joinHints);
        where = where == null ? new CanonicalFilterGroup(null, List.of(), List.of()) : where;
        having = having == null ? new CanonicalFilterGroup(null, List.of(), List.of()) : having;
        limit = limit <= 0 ? 100 : limit;
        offset = Math.max(offset, 0);
    }
}
