package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version.AstCanonicalizer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticSqlExecutor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanner;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanningInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.llm.SemanticAstGenerator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateTable;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryRuntimeService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryStageInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticAstGenerationStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticAstValidationStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticJoinPathStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticResultSummaryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticSqlCompileStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticSqlExecuteStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipeline;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipelineFactory;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.interceptor.AstCompilationInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlCompiler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.summary.SemanticResultSummarizer;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Example4SemanticFlowTest {

    @Mock
    private SemanticModelRegistry modelRegistry;
    @Mock
    private SemanticStagePipelineFactory stagePipelineFactory;
    @Mock
    private ObjectProvider<List<SemanticQueryStageInterceptor>> stageInterceptorsProvider;
    @Mock
    private AuditService auditService;
    @Mock
    private VerboseMessagePublisher verbosePublisher;

    @Mock
    private AstPlanner astPlanner;
    @Mock
    private SemanticAstGenerator astGenerator;
    @Mock
    private AstValidator astValidator;
    @Mock
    private SemanticSqlCompiler sqlCompiler;
    @Mock
    private SemanticSqlPolicyValidator sqlPolicyValidator;
    @Mock
    private SemanticSqlExecutor sqlExecutor;
    @Mock
    private SemanticResultSummarizer resultSummarizer;

    @Mock
    private ObjectProvider<List<AstPlanner>> plannersProvider;
    @Mock
    private ObjectProvider<List<AstPlanningInterceptor>> planningInterceptorsProvider;
    @Mock
    private ObjectProvider<List<SemanticAstGenerator>> astGeneratorsProvider;
    @Mock
    private ObjectProvider<List<AstValidator>> astValidatorsProvider;
    @Mock
    private ObjectProvider<List<SemanticSqlCompiler>> sqlCompilersProvider;
    @Mock
    private ObjectProvider<List<SemanticSqlPolicyValidator>> sqlValidatorsProvider;
    @Mock
    private ObjectProvider<List<AstCompilationInterceptor>> compilationInterceptorsProvider;
    @Mock
    private ObjectProvider<List<SemanticSqlExecutor>> sqlExecutorsProvider;
    @Mock
    private ObjectProvider<List<SemanticResultSummarizer>> summarizersProvider;
    @Mock
    private AstCanonicalizer astCanonicalizer;

    private ConvEngineMcpConfig mcpConfig;
    private SemanticQueryRuntimeService runtimeService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mcpConfig = new ConvEngineMcpConfig();
        mcpConfig.getDb().getQuery().setMode("semantic");
        mcpConfig.getDb().getSemantic().setEnabled(true);
        mcpConfig.getDb().getSemantic().setToolCode("db.semantic.query");

        when(stageInterceptorsProvider.getIfAvailable(any())).thenReturn(List.of());
        when(modelRegistry.getModel()).thenReturn(new SemanticModel(1, "demo_ops", "example4", Map.of(), List.of(), Map.of(), Map.of(), Map.of()));

        when(plannersProvider.getIfAvailable(any())).thenReturn(List.of(astPlanner));
        when(planningInterceptorsProvider.getIfAvailable(any())).thenReturn(List.of());
        when(astGeneratorsProvider.getIfAvailable(any())).thenReturn(List.of(astGenerator));
        when(astValidatorsProvider.getIfAvailable(any())).thenReturn(List.of(astValidator));
        when(sqlCompilersProvider.getIfAvailable(any())).thenReturn(List.of(sqlCompiler));
        when(sqlValidatorsProvider.getIfAvailable(any())).thenReturn(List.of(sqlPolicyValidator));
        when(compilationInterceptorsProvider.getIfAvailable(any())).thenReturn(List.of());
        when(sqlExecutorsProvider.getIfAvailable(any())).thenReturn(List.of(sqlExecutor));
        when(summarizersProvider.getIfAvailable(any())).thenReturn(List.of(resultSummarizer));

        when(astPlanner.supports(any())).thenReturn(true);
        when(astGenerator.supports(any())).thenReturn(true);
        when(astValidator.supports(any())).thenReturn(true);
        when(sqlCompiler.supports(any())).thenReturn(true);
        when(sqlPolicyValidator.supports(any())).thenReturn(true);
        when(sqlExecutor.supports(any())).thenReturn(true);
        when(resultSummarizer.supports(any())).thenReturn(true);

        when(astPlanner.plan(any(), any())).thenAnswer(inv -> new JoinPathPlan(
                "zp_disco_request",
                List.of(new SchemaEdge("zp_disco_request", "account_id", "zp_account", "account_id", "FK", "INNER")),
                List.of("zp_disco_request", "zp_account"),
                List.of(),
                0.91
        ));
        when(astGenerator.generate(anyString(), any(), any(), any())).thenAnswer(inv -> new AstGenerationResult(
                new SemanticQueryAstV1(
                        "v1",
                        "DisconnectRequest",
                        List.of("requestId", "requestStatus"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        100,
                        0,
                        false,
                        List.of()
                ),
                "{\"entity\":\"DisconnectRequest\"}",
                false
        ));
        when(astCanonicalizer.fromV1(any())).thenReturn(new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null), new CanonicalProjection("requestStatus", null)),
                null,
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
        ));
        when(astValidator.validate(any(), any(), any(), any())).thenReturn(new AstValidationResult(true, List.of()));
        when(sqlCompiler.compile(any())).thenReturn(new CompiledSql("select request_id as requestId, request_status as requestStatus from zp_disco_request limit :limit", Map.of("limit", 100)));
        when(sqlExecutor.execute(any(), any())).thenAnswer(inv -> new SemanticExecutionResult(2, List.of(
                Map.of("requestId", "ZPR1003", "requestStatus", "SUBMITTED"),
                Map.of("requestId", "ZPR1002", "requestStatus", "INVENTORY_ERROR")
        )));
        when(resultSummarizer.summarize(any(), any())).thenReturn("Example4 summary");

        SemanticQueryStage retrieval = new StageTest("retrieval", context ->
                context.retrieval(new RetrievalResult(
                        context.question(),
                        List.of(new CandidateEntity("DisconnectRequest", 0.95, 0.85, 0.10, List.of("keyword+entity"))),
                        List.of(
                                new CandidateTable("zp_disco_request", "DisconnectRequest", 0.93, 0.83, 0.10, List.of("request")),
                                new CandidateTable("zp_disco_trans_data", "DisconnectRequest", 0.90, 0.80, 0.10, List.of("disconnect"))
                        ),
                        "HIGH"
                )));

        SemanticJoinPathStage joinPathStage = new SemanticJoinPathStage(plannersProvider, planningInterceptorsProvider, mcpConfig, auditService, verbosePublisher);
        SemanticAstGenerationStage astGenerationStage = new SemanticAstGenerationStage(astGeneratorsProvider, astCanonicalizer, modelRegistry, mcpConfig, auditService, verbosePublisher);
        SemanticAstValidationStage astValidationStage = new SemanticAstValidationStage(modelRegistry, astValidatorsProvider, mcpConfig, auditService, verbosePublisher);
        SemanticSqlCompileStage sqlCompileStage = new SemanticSqlCompileStage(sqlCompilersProvider, sqlValidatorsProvider, compilationInterceptorsProvider, mcpConfig, auditService, verbosePublisher);
        SemanticSqlExecuteStage sqlExecuteStage = new SemanticSqlExecuteStage(sqlExecutorsProvider, mcpConfig, auditService, verbosePublisher);
        SemanticResultSummaryStage summaryStage = new SemanticResultSummaryStage(summarizersProvider, mcpConfig, auditService, verbosePublisher);

        when(stagePipelineFactory.create()).thenReturn(new SemanticStagePipeline(List.of(
                retrieval,
                joinPathStage,
                astGenerationStage,
                astValidationStage,
                sqlCompileStage,
                sqlExecuteStage,
                summaryStage
        )));

        runtimeService = new SemanticQueryRuntimeService(
                mcpConfig,
                modelRegistry,
                stagePipelineFactory,
                stageInterceptorsProvider,
                auditService,
                verbosePublisher
        );
    }

    @Test
    void example4PromptsRunEndToEndWithNewSemanticDeterminantsAndMeta() {
        List<String> example4Prompts = List.of(
                "show failed disconnect requests in last 24 hours",
                "why did request ZPR1003 fail",
                "show account status for UPSA100",
                "list downstream checks for DON9001"
        );

        for (String prompt : example4Prompts) {
            EngineSession session = session(prompt);
            Map<String, Object> out = runtimeService.plan(prompt, session);

            assertEquals("completed", out.get("next"));
            assertNotNull(out.get("compiledSql"));
            assertNotNull(out.get("execution"));
            assertEquals("Example4 summary", out.get("summary"));
            Map<?, ?> meta = assertInstanceOf(Map.class, out.get("_meta"));
            assertEquals(Boolean.TRUE, meta.get("astPrepared"));
            assertEquals(Boolean.TRUE, meta.get("astValidated"));
            assertEquals(Boolean.TRUE, meta.get("astValid"));
            assertEquals(Boolean.TRUE, meta.get("sqlCompiled"));
            assertEquals(Boolean.TRUE, meta.get("sqlExecuted"));
        }

        verify(auditService, atLeast(4)).audit(eq("JOIN_PATH_RESOLVED"), any(UUID.class), any(Map.class));
        verify(auditService, atLeast(4)).audit(eq("AST_GENERATED"), any(UUID.class), any(Map.class));
        verify(auditService, atLeast(4)).audit(eq("AST_VALIDATED"), any(UUID.class), any(Map.class));

        ArgumentCaptor<String> determinantCaptor = ArgumentCaptor.forClass(String.class);
        verify(verbosePublisher, atLeast(1)).publish(any(), anyString(), determinantCaptor.capture(), isNull(), eq("db.semantic.query"), anyBoolean(), anyMap());
        List<String> determinants = determinantCaptor.getAllValues();
        assertTrue(determinants.contains("JOIN_PATH_RESOLVED"));
        assertTrue(determinants.contains("AST_GENERATED"));
        assertTrue(determinants.contains("AST_VALIDATED"));
    }

    private EngineSession session(String userText) {
        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText)
                .inputParams(new LinkedHashMap<>())
                .build();
        EngineSession session = new EngineSession(context, new ObjectMapper());
        session.setIntent("SEMANTIC_QUERY");
        session.setState("ANALYZE");
        return session;
    }

    private static class StageTest implements SemanticQueryStage {
        private final String code;
        private final Consumer<SemanticQueryContext> handler;

        private StageTest(String code, Consumer<SemanticQueryContext> handler) {
            this.code = code;
            this.handler = handler;
        }

        @Override
        public String stageCode() {
            return code;
        }

        @Override
        public void execute(SemanticQueryContext context) {
            handler.accept(context);
        }
    }
}
