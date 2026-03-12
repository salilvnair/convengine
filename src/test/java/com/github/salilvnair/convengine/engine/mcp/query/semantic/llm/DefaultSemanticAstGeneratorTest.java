package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize.AstNormalizer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateTable;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSemanticAstGeneratorTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private SemanticModelRegistry modelRegistry;
    @Mock
    private ObjectProvider<List<SemanticAstGenerationInterceptor>> interceptorsProvider;
    @Mock
    private AuditService auditService;
    @Mock
    private VerboseMessagePublisher verbosePublisher;
    @Mock
    private CeConfigResolver configResolver;
    @Mock
    private PromptTemplateRenderer renderer;
    @Mock
    private AstNormalizer astNormalizer;

    private DefaultSemanticAstGenerator generator;
    private EngineSession session;
    private RetrievalResult retrieval;
    private JoinPathPlan joinPathPlan;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(interceptorsProvider.getIfAvailable(any())).thenReturn(List.of());
        when(modelRegistry.getModel()).thenReturn(new SemanticModel(
                1,
                "demo_ops",
                "test model",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        ));
        generator = new DefaultSemanticAstGenerator(
                llmClient,
                modelRegistry,
                interceptorsProvider,
                auditService,
                verbosePublisher,
                configResolver,
                renderer,
                astNormalizer
        );
        lenient().when(astNormalizer.normalize(any(), any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(configResolver.resolveString(eq(generator), eq("SYSTEM_PROMPT"), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(configResolver.resolveString(eq(generator), eq("USER_PROMPT"), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(configResolver.resolveString(eq(generator), eq("SCHEMA_PROMPT"), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(renderer.render(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        generator.init();

        session = session("show failed disconnect requests");
        retrieval = new RetrievalResult(
                "show failed disconnect requests",
                List.of(new CandidateEntity("DisconnectRequest", 0.98, 0.80, 0.18, List.of("match"))),
                List.of(new CandidateTable("zp_disco_request", "DisconnectRequest", 0.95, 0.77, 0.18, List.of("match"))),
                "HIGH"
        );
        joinPathPlan = new JoinPathPlan(
                "zp_disco_request",
                List.of(new SchemaEdge("zp_disco_request", "account_id", "zp_account", "account_id", "FK", "INNER")),
                List.of("zp_disco_request", "zp_account"),
                List.of(),
                0.9
        );
    }

    @Test
    void emitsLlmInputAndOutputAuditAndVerboseWithMeta() {
        when(llmClient.generateJsonStrict(any(), anyString(), anyString(), anyString()))
                .thenReturn("{\"entity\":\"DisconnectRequest\",\"select\":[\"requestId\"],\"filters\":[],\"limit\":50}");

        AstGenerationResult out = generator.generate("show failed disconnect requests", retrieval, joinPathPlan, session);

        assertEquals("DisconnectRequest", out.ast().entity());
        assertEquals(50, out.ast().limit());

        verify(auditService, times(1)).audit(eq("AST_INPUT"), eq(session.getConversationId()), any(Map.class));
        verify(auditService, times(1)).audit(eq("AST_OUTPUT"), eq(session.getConversationId()), any(Map.class));
        verify(auditService, times(0)).audit(eq("AST_ERROR"), eq(session.getConversationId()), any(Map.class));

        verify(verbosePublisher, times(2)).publish(
                eq(session),
                eq("DefaultSemanticAstGenerator"),
                anyString(),
                isNull(),
                eq("db.semantic.query"),
                anyBoolean(),
                anyMap()
        );

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService, atLeastOnce()).audit(eq("AST_OUTPUT"), eq(session.getConversationId()), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertTrue(payload.containsKey("_meta"));
        Map<?, ?> meta = assertInstanceOf(Map.class, payload.get("_meta"));
        assertEquals(Boolean.TRUE, meta.get("llmInvoked"));
        assertEquals(Boolean.TRUE, meta.get("astParsed"));
        assertEquals(Boolean.TRUE, meta.get("astPrepared"));

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService, atLeastOnce()).audit(eq("AST_INPUT"), eq(session.getConversationId()), inputCaptor.capture());
        Map<String, Object> inputPayload = inputCaptor.getValue();
        assertTrue(inputPayload.containsKey("relevant_metrics"));
        assertTrue(inputPayload.containsKey("matched_intent_rules"));
        assertTrue(inputPayload.containsKey("relevant_value_patterns"));
        assertTrue(inputPayload.containsKey("relevant_relationships"));
        assertTrue(inputPayload.containsKey("relevant_join_hints"));
        assertTrue(inputPayload.containsKey("relevant_synonyms"));
        assertTrue(inputPayload.containsKey("relevant_rules"));
    }

    @Test
    void emitsLlmErrorAuditAndVerboseOnFailure() {
        when(llmClient.generateJsonStrict(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("llm down"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.generate("show failed disconnect requests", retrieval, joinPathPlan, session));
        assertTrue(ex.getMessage().contains("Failed to generate semantic AST"));

        verify(auditService, times(1)).audit(eq("AST_INPUT"), eq(session.getConversationId()), any(Map.class));
        verify(auditService, times(0)).audit(eq("AST_OUTPUT"), eq(session.getConversationId()), any(Map.class));
        verify(auditService, times(1)).audit(eq("AST_ERROR"), eq(session.getConversationId()), any(Map.class));

        ArgumentCaptor<String> determinantCaptor = ArgumentCaptor.forClass(String.class);
        verify(verbosePublisher, atLeastOnce()).publish(
                eq(session),
                eq("DefaultSemanticAstGenerator"),
                determinantCaptor.capture(),
                isNull(),
                eq("db.semantic.query"),
                anyBoolean(),
                anyMap()
        );
        List<String> determinants = determinantCaptor.getAllValues();
        assertTrue(determinants.contains("AST_INPUT"));
        assertTrue(determinants.contains("AST_ERROR"));
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
}
