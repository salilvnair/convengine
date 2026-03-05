package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.compiler;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstClauseHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstExistsHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstFunctionHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstMetricHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstPredicateHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstSubqueryHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.registry.AstWindowHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.orchestrator.DefaultAstQueryAssemblyClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultExistsClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultFunctionClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultMetricClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultPredicateClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultSubqueryClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.clause.provider.DefaultWindowClauseHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider.DefaultBetweenAstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider.DefaultComparisonAstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider.DefaultInAstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider.DefaultNullCheckAstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.core.AstWindowFunctionHandlerRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.window.provider.DefaultRowNumberWindowFunctionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order()
public class DefaultSemanticSqlCompiler implements AstSqlCompiler {

    private final SemanticModelRegistry modelRegistry;
    private final AstClauseHandlerRegistry clauseHandlerRegistry;

    public DefaultSemanticSqlCompiler(SemanticModelRegistry modelRegistry, ConvEngineMcpConfig mcpConfig) {
        this(modelRegistry, buildClauseRegistry(mcpConfig));
    }

    @Autowired
    public DefaultSemanticSqlCompiler(SemanticModelRegistry modelRegistry, AstClauseHandlerRegistry clauseHandlerRegistry) {
        this.clauseHandlerRegistry = clauseHandlerRegistry;
        this.modelRegistry = modelRegistry;
    }

    private static AstClauseHandlerRegistry buildClauseRegistry(ConvEngineMcpConfig mcpConfig) {
        var operatorRegistry = new AstOperatorHandlerRegistry(List.of(
                new DefaultNullCheckAstOperatorHandler(),
                new DefaultBetweenAstOperatorHandler(),
                new DefaultInAstOperatorHandler(),
                new DefaultComparisonAstOperatorHandler()
        ));
        var windowFunctionRegistry = new AstWindowFunctionHandlerRegistry(List.of(
                new DefaultRowNumberWindowFunctionHandler()
        ));

        var predicate = new DefaultPredicateClauseHandler(operatorRegistry);
        var exists = new DefaultExistsClauseHandler(predicate);
        var subquery = new DefaultSubqueryClauseHandler(predicate);
        var metric = new DefaultMetricClauseHandler(predicate);
        var window = new DefaultWindowClauseHandler(predicate, windowFunctionRegistry);
        var function = new DefaultFunctionClauseHandler();

        return new AstClauseHandlerRegistry(List.of(
                new DefaultAstQueryAssemblyClauseHandler(
                        mcpConfig,
                        new AstPredicateHandlerRegistry(List.of(predicate)),
                        new AstFunctionHandlerRegistry(List.of(function)),
                        new AstWindowHandlerRegistry(List.of(window)),
                        new AstExistsHandlerRegistry(List.of(exists)),
                        new AstSubqueryHandlerRegistry(List.of(subquery)),
                        new AstMetricHandlerRegistry(List.of(metric))
                )
        ));
    }

    @Override
    public CompiledSql compile(SemanticQueryContext context) {
        if (context == null || context.canonicalAst() == null) {
            throw new IllegalStateException(SemanticSqlConstants.ERROR_AST_MISSING);
        }

        CanonicalAst ast = context.canonicalAst();
        SemanticModel model = modelRegistry.getModel();
        SemanticEntity entity = model.entities().get(ast.entity());
        if (entity == null) {
            throw new IllegalStateException(SemanticSqlConstants.ERROR_UNKNOWN_ENTITY_PREFIX + ast.entity());
        }

        CompileWorkPlan plan = new CompileWorkPlan(context, ast, model, entity, context.joinPath());
        clauseHandlerRegistry.applyAll(plan);

        if (plan.getCompiledSql() == null || plan.getCompiledSql().sql() == null || plan.getCompiledSql().sql().isBlank()) {
            throw new IllegalStateException(SemanticSqlConstants.ERROR_SQL_NOT_PRODUCED);
        }
        return plan.getCompiledSql();
    }
}
