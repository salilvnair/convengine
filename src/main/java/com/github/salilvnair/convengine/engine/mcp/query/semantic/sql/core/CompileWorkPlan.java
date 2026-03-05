package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jooq.SelectQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public class CompileWorkPlan {
    private final SemanticQueryContext context;
    private final CanonicalAst ast;
    private final SemanticModel model;
    private final SemanticEntity entity;
    private final JoinPathPlan joinPath;

    @Setter
    private CompiledSql compiledSql;

    @Setter
    private String baseTable;

    @Setter
    private List<SchemaEdge> effectiveEdges;

    @Setter
    private Map<String, String> aliasByTable;

    @Setter
    private SelectQuery<?> query;

    @Setter
    private Map<String, Object> params = new LinkedHashMap<>();

    @Setter
    private int[] paramIdx = new int[]{1};
}
