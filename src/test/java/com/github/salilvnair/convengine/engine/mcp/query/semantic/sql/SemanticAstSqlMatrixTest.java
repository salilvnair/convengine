package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstLogicalOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.DefaultAstValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalSubquerySpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SortDirection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticMetric;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationshipEnd;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.compiler.DefaultSemanticSqlCompiler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticAstSqlMatrixTest {

    private SemanticModel model;
    private ConvEngineMcpConfig config;

    @BeforeEach
    void setUp() {
        config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setMaxLimit(500);
        model = new SemanticModel(
                1,
                "demo_ops",
                "test",
                Map.of(
                        "DisconnectRequest",
                        new SemanticEntity(
                                "Disconnect diagnostics",
                                List.of(),
                                new SemanticEntityTables("zp_disco_request", List.of("zp_disco_trans_data")),
                                Map.of(
                                        "requestId", new SemanticField("zp_disco_request.request_id", "uuid", null, true, true, true),
                                        "requestStatus", new SemanticField("zp_disco_request.request_status", "string", null, true, true, false),
                                        "requestedAt", new SemanticField("zp_disco_request.requested_at", "timestamp", null, true, true, false),
                                        "accountId", new SemanticField("zp_disco_request.account_id", "string", null, true, true, false),
                                        "teamNotes", new SemanticField("zp_disco_trans_data.notes_text", "string", null, true, true, false)
                                )
                        )
                ),
                List.of(
                        new SemanticRelationship(
                                "request-ui",
                                "request to ui",
                                new SemanticRelationshipEnd("zp_disco_request", "request_id"),
                                new SemanticRelationshipEnd("zp_disco_trans_data", "request_id"),
                                "one_to_one"
                        )
                ),
                Map.of(),
                Map.of(),
                Map.of(
                        "failed_count", new SemanticMetric("Failed count", "COUNT(CASE WHEN zp_disco_request.request_status = 'FAILED' THEN 1 END)")
                )
        );
    }

    @Test
    void validatorRejectsUnknownFieldAndMetricOutsideHaving() {
        @SuppressWarnings("unchecked")
        ObjectProvider<List<com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationInterceptor>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(List.of());
        DefaultAstValidator validator = new DefaultAstValidator(config, provider);
        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("unknownField", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("failed_count", AstOperator.GT, 1)
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of("failed_count"),
                null,
                List.of(),
                List.of(),
                100,
                0,
                false,
                List.of()
        );

        AstValidationResult result = validator.validate(ast, model, null, null);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("unknown select field")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("metric filter is allowed only in having")));
    }

    @Test
    void compilerBuildsWhereGroupByHavingSortAndPagination() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("accountId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("requestStatus", AstOperator.EQ, "FAILED"),
                        new CanonicalFilter("requestedAt", AstOperator.GTE, "now-24h")
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of("accountId"),
                List.of("failed_count"),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("failed_count", AstOperator.GT, 2)
                ), List.of()),
                List.of(),
                List.of(new CanonicalSort("accountId", SortDirection.ASC, null)),
                50,
                10,
                true,
                List.of()
        );

        SemanticQueryContext context = new SemanticQueryContext("failed by account", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));

        CompiledSql sql = compiler.compile(context);
        assertTrue(sql.sql().toLowerCase().contains("select distinct"));
        assertTrue(sql.sql().toLowerCase().contains("group by"));
        assertTrue(sql.sql().toLowerCase().contains("having"));
        assertTrue(sql.sql().toLowerCase().contains("order by"));
        assertTrue(sql.params().containsKey("__limit"));
        assertTrue(sql.params().containsKey("__offset"));
    }

    @Test
    void compilerSupportsBooleanTreeAndBetweenOperator() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalFilterGroup nested = new CanonicalFilterGroup(
                AstLogicalOperator.OR,
                List.of(new CanonicalFilter("requestStatus", AstOperator.EQ, "FAILED")),
                List.of(new CanonicalFilterGroup(
                        AstLogicalOperator.AND,
                        List.of(new CanonicalFilter("requestedAt", AstOperator.BETWEEN, List.of("last_7d", "now"))),
                        List.of()
                ))
        );

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                nested,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                20,
                0,
                false,
                List.of()
        );

        SemanticQueryContext context = new SemanticQueryContext("nested", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));

        CompiledSql sql = compiler.compile(context);
        String lowered = sql.sql().toLowerCase();
        assertTrue(lowered.contains(" between "));
        assertTrue(lowered.contains(" or "));
    }

    @Test
    void compilerWrapsIlikeLiteralWithWildcardsWhenMissing() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("requestStatus", AstOperator.ILIKE, "failed")
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                20,
                0,
                false,
                List.of()
        );

        SemanticQueryContext context = new SemanticQueryContext("ilike", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));

        CompiledSql sql = compiler.compile(context);
        assertTrue(sql.sql().toLowerCase().contains(" ilike "));
        assertEquals("%failed%", sql.params().get("p1"));
    }

    @Test
    void compilerSupportsExistsNotExistsAndSubqueryFilter() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(), List.of()),
                null,
                List.of(new CanonicalExistsBlock("DisconnectRequest",
                        new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(new CanonicalFilter("requestStatus", AstOperator.EQ, "FAILED")), List.of()),
                        false)),
                List.of(new CanonicalSubqueryFilter("requestId", AstOperator.EQ,
                        new CanonicalSubquerySpec("DisconnectRequest", "requestId",
                                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(new CanonicalFilter("requestStatus", AstOperator.EQ, "FAILED")), List.of()),
                                List.of(), null, 1))),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                20,
                0,
                false,
                List.of()
        );
        SemanticQueryContext context = new SemanticQueryContext("exists", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));

        CompiledSql sql = compiler.compile(context);
        String lowered = sql.sql().toLowerCase();
        assertTrue(lowered.contains("exists (select 1"));
        assertTrue(lowered.contains("(select"));
    }

    @Test
    void compilerSupportsWindowRowNumber() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(new CanonicalWindowSpec(
                        "rn",
                        "ROW_NUMBER",
                        List.of("accountId"),
                        List.of(new CanonicalSort("requestedAt", SortDirection.DESC, null))
                )),
                List.of(),
                10,
                0,
                false,
                List.of()
        );
        SemanticQueryContext context = new SemanticQueryContext("window", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));
        CompiledSql sql = compiler.compile(context);
        assertTrue(sql.sql().toLowerCase().contains("row_number() over"));
    }

    @Test
    void compilerAddsJoinWhenFilterFieldLivesOnDifferentTableThanSelectedProjection() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("teamNotes", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("requestId", AstOperator.EQ, "ZPR1003")
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                100,
                0,
                false,
                List.of()
        );

        SemanticQueryContext context = new SemanticQueryContext("teamNotes by request", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_trans_data", List.of(), List.of("zp_disco_trans_data"), List.of(), 1.0
        ));

        CompiledSql sql = compiler.compile(context);
        String lowered = sql.sql().toLowerCase();
        assertTrue(lowered.contains("from \"zp_disco_trans_data\""));
        assertTrue(lowered.contains("join \"zp_disco_request\""));
        assertTrue(lowered.contains("\"t1\".\"request_id\" = :p1"));
    }

    @Test
    void compilerSupportsGroupedWindowOrderByNonGroupedField() {
        SemanticModelRegistry registry = mock(SemanticModelRegistry.class);
        when(registry.getModel()).thenReturn(model);
        DefaultSemanticSqlCompiler compiler = new DefaultSemanticSqlCompiler(registry, config);

        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("accountId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of("accountId"),
                List.of(),
                null,
                List.of(new CanonicalWindowSpec(
                        "account_rank",
                        "ROW_NUMBER",
                        List.of(),
                        List.of(new CanonicalSort("requestId", SortDirection.DESC, null))
                )),
                List.of(new CanonicalSort("accountId", SortDirection.ASC, null)),
                10,
                0,
                false,
                List.of()
        );
        SemanticQueryContext context = new SemanticQueryContext("grouped-window", null);
        context.canonicalAst(ast);
        context.joinPath(new com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan(
                "zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0
        ));
        CompiledSql sql = compiler.compile(context);
        String lowered = sql.sql().toLowerCase();
        assertTrue(lowered.contains("group by"));
        assertTrue(lowered.contains("row_number() over"));
        assertTrue(lowered.contains("max("));
    }
}
