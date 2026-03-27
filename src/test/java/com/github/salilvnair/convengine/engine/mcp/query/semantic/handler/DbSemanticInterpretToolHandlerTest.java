package com.github.salilvnair.convengine.engine.mcp.query.semantic.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.handler.DbSemanticInterpretToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelDynamicOverlayService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticInterpretResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.service.SemanticInterpretService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbSemanticInterpretToolHandlerTest {

    private StubLlmClient llmClient;
    private DbSemanticInterpretToolHandler handler;

    @BeforeEach
    void setUp() {
        ConvEngineMcpConfig config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setEnabled(true);

        llmClient = new StubLlmClient();
        llmClient.response = """
                {
                  "canonicalIntent": {
                    "intent": "LIST_REQUESTS",
                    "entity": "REQUEST",
                    "queryClass": "LIST_REQUESTS",
                    "filters": [],
                    "sort": [{"field":"created_at","direction":"DESC"}],
                    "limit": 100
                  },
                  "confidence": 0.95,
                  "needsClarification": false,
                  "clarificationQuestion": null,
                  "ambiguities": []
                }
                """;

        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> noJdbc = factory.getBeanProvider(NamedParameterJdbcTemplate.class);
        SemanticModelRegistry registry = new SemanticModelRegistry(
                new SemanticModelDynamicOverlayService(noJdbc),
                new SemanticModelValidator()
        );
        registry.setModel(new SemanticModel(
                1,
                "demo_ops",
                "test",
                Map.of(
                        "REQUEST", new SemanticEntity(
                                "Request",
                                List.of("request"),
                                new SemanticEntityTables("zp_disco_request", List.of()),
                                Map.of(
                                        "customer", new SemanticField("zp_disco_request.customer_name", "string", null, true, true, false, List.of("customer name"), List.of()),
                                        "status", new SemanticField("zp_disco_request.status", "string", null, true, true, false, List.of("request status"), List.of("FAILED", "REJECTED"))
                                )
                        )
                ),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        ));
        SemanticInterpretService service = new SemanticInterpretService(
                llmClient,
                config,
                new NoopAuditService(),
                null,
                null,
                null,
                registry,
                noJdbc
        );
        handler = new DbSemanticInterpretToolHandler(service);
    }

    @Test
    void readsQuestionFromQueryArg() {
        EngineSession session = session("fallback question");
        Object out = handler.execute(null, Map.of("query", "show failed requests today"), session);

        SemanticInterpretResponse response = (SemanticInterpretResponse) out;
        assertNotNull(response);
        assertEquals("show failed requests today", response.question());
    }

    @Test
    void fallsBackToSessionUserTextWhenArgsMissing() {
        EngineSession session = session("show rejected relinks for ups");
        Object out = handler.execute(null, Map.of(), session);

        SemanticInterpretResponse response = (SemanticInterpretResponse) out;
        assertNotNull(response);
        assertEquals("show rejected relinks for ups", response.question());
    }

    @Test
    void prefersStandaloneQueryArgOverUserInput() {
        EngineSession session = session("/sql2 raw text");
        Object out = handler.execute(null, Map.of(
                "user_input", "/sql2 customer_name = UPS across all locations",
                "standalone_query", "Show all disconnect requests where customer is UPS across all locations"
        ), session);

        SemanticInterpretResponse response = (SemanticInterpretResponse) out;
        assertNotNull(response);
        assertEquals("Show all disconnect requests where customer is UPS across all locations", response.question());
    }

    @Test
    void fallsBackToSessionStandaloneQueryBeforeUserText() {
        EngineSession session = session("/sql2 customer_name = UPS across all locations");
        session.setStandaloneQuery("Show all disconnect requests where customer is UPS across all locations");
        Object out = handler.execute(null, Map.of(), session);

        SemanticInterpretResponse response = (SemanticInterpretResponse) out;
        assertNotNull(response);
        assertEquals("Show all disconnect requests where customer is UPS across all locations", response.question());
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

    private static final class StubLlmClient implements LlmClient {
        private String response = "{}";

        @Override
        public String generateText(EngineSession session, String hint, String contextJson) {
            return response;
        }

        @Override
        public String generateJson(EngineSession session, String hint, String jsonSchema, String contextJson) {
            return response;
        }

        @Override
        public float[] generateEmbedding(EngineSession session, String input) {
            return new float[0];
        }

        @Override
        public String generateJsonStrict(EngineSession session, String hint, String jsonSchema, String context) {
            return response;
        }
    }

    private static final class NoopAuditService implements AuditService {
        @Override
        public void audit(String stage, UUID conversationId, String payloadJson) {
            // no-op
        }
    }
}
