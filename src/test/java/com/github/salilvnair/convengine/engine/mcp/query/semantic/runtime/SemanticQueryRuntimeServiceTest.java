package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryRuntimeService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryStageInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipeline;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipelineFactory;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticQueryRuntimeServiceTest {

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
        when(modelRegistry.getModel()).thenReturn(new SemanticModel(1, "demo_ops", "test", Map.of(), List.of(), Map.of(), Map.of(), Map.of()));

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
    void stageAuditAndVerboseAreEmittedForEveryStageWithMeta() {
        SemanticQueryStage retrieval = new SemanticRetrievalStageTest("retrieval", context ->
                context.retrieval(new com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult(
                        context.question(),
                        List.of(),
                        List.of(),
                        "HIGH"
                )));
        SemanticQueryStage ast = new SemanticAstStageTest("ast", context -> {
            SemanticQueryAstV1 queryAst = new SemanticQueryAstV1(
                    "v1",
                    "DisconnectRequest",
                    List.of("requestId"),
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
                    50,
                    0,
                    false,
                    List.of()
            );
            context.astGeneration(new AstGenerationResult(queryAst, "{\"entity\":\"DisconnectRequest\"}", false));
            context.astValidation(new AstValidationResult(true, List.of()));
            context.compiledSql(new CompiledSql("select request_id from zp_disco_request limit :limit", Map.of("limit", 50)));
            context.executionResult(new SemanticExecutionResult(1, List.of(Map.of("request_id", "ZPR1003"))));
            context.summary("1 row found");
        });
        SemanticQueryStage summary = new SemanticSummaryStageTest("summary", context -> {
            // no-op terminal mock stage
        });

        when(stagePipelineFactory.create()).thenReturn(new SemanticStagePipeline(List.of(retrieval, ast, summary)));

        EngineSession session = session("what failed?");
        Map<String, Object> out = runtimeService.plan("what failed?", session);

        assertEquals("completed", out.get("next"));
        assertTrue(out.containsKey("_meta"));
        Map<?, ?> outMeta = assertInstanceOf(Map.class, out.get("_meta"));
        assertEquals(Boolean.TRUE, outMeta.get("astPrepared"));
        assertEquals(Boolean.TRUE, outMeta.get("astValidated"));
        assertEquals(Boolean.TRUE, outMeta.get("astValid"));
        assertEquals(Boolean.TRUE, outMeta.get("sqlCompiled"));
        assertEquals(Boolean.TRUE, outMeta.get("sqlExecuted"));
        assertEquals(Boolean.TRUE, outMeta.get("summaryPrepared"));

        verify(auditService, times(0)).audit(eq("RUNTIME_ERROR"), eq(session.getConversationId()), any(Map.class));
    }

    @Test
    void failingStageEmitsStageErrorAndRuntimeErrorAuditAndVerbose() {
        SemanticQueryStage retrieval = new SemanticRetrievalStageTest("retrieval", context ->
                context.retrieval(new com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult(
                        context.question(),
                        List.of(),
                        List.of(),
                        "LOW"
                )));
        SemanticQueryStage failing = new SemanticAstStageTest("ast", context -> {
            throw new IllegalStateException("boom");
        });

        when(stagePipelineFactory.create()).thenReturn(new SemanticStagePipeline(List.of(retrieval, failing)));

        EngineSession session = session("why failed");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runtimeService.plan("why failed", session));
        assertEquals("boom", ex.getMessage());

        verify(auditService, times(1)).audit(eq("AST_ERROR"), eq(session.getConversationId()), any(Map.class));
        verify(auditService, times(1)).audit(eq("RUNTIME_ERROR"), eq(session.getConversationId()), any(Map.class));

        ArgumentCaptor<String> determinantCaptor = ArgumentCaptor.forClass(String.class);
        verify(verbosePublisher, atLeastOnce()).publish(
                eq(session),
                anyString(),
                determinantCaptor.capture(),
                isNull(),
                eq("db.semantic.query"),
                anyBoolean(),
                anyMap()
        );
        List<String> determinants = determinantCaptor.getAllValues();
        assertTrue(determinants.contains("AST_ERROR"));
        assertTrue(determinants.contains("RUNTIME_ERROR"));
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

    private static class SemanticRetrievalStageTest implements SemanticQueryStage {
        private final String code;
        private final Consumer<SemanticQueryContext> handler;

        private SemanticRetrievalStageTest(String code, Consumer<SemanticQueryContext> handler) {
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

    private static class SemanticAstStageTest implements SemanticQueryStage {
        private final String code;
        private final Consumer<SemanticQueryContext> handler;

        private SemanticAstStageTest(String code, Consumer<SemanticQueryContext> handler) {
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

    private static class SemanticSummaryStageTest implements SemanticQueryStage {
        private final String code;
        private final Consumer<SemanticQueryContext> handler;

        private SemanticSummaryStageTest(String code, Consumer<SemanticQueryContext> handler) {
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
