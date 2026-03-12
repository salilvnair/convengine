package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelDynamicOverlayService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelLoader;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticInterpretRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticInterpretResponse;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticInterpretServiceTest {

    private ConvEngineMcpConfig mcpConfig;
    private StubLlmClient llmClient;
    private SemanticInterpretService service;

    @BeforeEach
    void setUp() {
        mcpConfig = new ConvEngineMcpConfig();
        mcpConfig.getDb().getSemantic().setEnabled(true);
        mcpConfig.getDb().getSemantic().setDefaultLimit(100);
        mcpConfig.getDb().getSemantic().setMaxLimit(500);
        mcpConfig.getDb().getSemantic().setTimezone("America/Chicago");
        mcpConfig.getDb().getSemantic().getClarification().setConfidenceThreshold(0.80d);

        llmClient = new StubLlmClient();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> noJdbc = factory.getBeanProvider(NamedParameterJdbcTemplate.class);
        SemanticModelLoader loader = new SemanticModelLoader(mcpConfig, new DefaultResourceLoader());
        SemanticModelRegistry registry = new SemanticModelRegistry(
                loader,
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
                                        "status", new SemanticField("zp_disco_request.status", "string", null, true, true, false, List.of("request status"), List.of("REJECTED", "FAILED", "APPROVED")),
                                        "customer", new SemanticField("zp_disco_request.customer_name", "string", null, true, true, false, List.of("customer name"), List.of()),
                                        "createdAt", new SemanticField("zp_disco_request.created_at", "timestamp", null, true, true, false, List.of("created at"), List.of())
                                )
                        )
                ),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        ));
        service = new SemanticInterpretService(
                llmClient,
                mcpConfig,
                new NoopAuditService(),
                null,
                null,
                null,
                registry,
                noJdbc
        );
    }

    @Test
    void parsesStrictJsonFromLlm() {
        llmClient.response = """
                {
                  "canonicalIntent": {
                    "intent": "LIST_REQUESTS",
                    "entity": "REQUEST",
                    "queryClass": "LIST_REQUESTS",
                    "filters": [
                      {"field":"status","op":"EQ","value":"REJECTED"},
                      {"field":"request_type","op":"EQ","value":"RELINK"},
                      {"field":"customer","op":"EQ","value":"UPS"}
                    ],
                    "timeRange": {"kind":"RELATIVE","value":"TODAY","timezone":"America/Chicago"},
                    "sort": [{"field":"created_at","direction":"DESC"}],
                    "limit": 100
                  },
                  "confidence": 0.91,
                  "needsClarification": false,
                  "clarificationQuestion": null,
                  "ambiguities": [],
                  "trace": {"normalizations":["rejected->REJECTED"]}
                }
                """;

        SemanticInterpretRequest request = new SemanticInterpretRequest(
                "show rejected relinks for ups today",
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("timezone", "America/Chicago")
        );

        SemanticInterpretResponse response = service.interpret(request, session(request.question()));

        assertNotNull(response);
        assertEquals("LIST_REQUESTS", response.canonicalIntent().intent());
        assertEquals("REQUEST", response.canonicalIntent().entity());
        assertTrue(response.canonicalIntent().filters().size() >= 2);
        assertTrue(response.meta().confidence() >= 0.80d || response.meta().needsClarification());
        assertTrue(response.meta().confidence() >= 0.0d);
    }

    @Test
    void parsesJsonFromCodeFenceWhenStrictParseFails() {
        llmClient.response = """
                Here is the result:
                ```json
                {
                  "canonicalIntent": {
                    "intent": "LIST_REQUESTS",
                    "entity": "REQUEST",
                    "queryClass": "LIST_REQUESTS",
                    "filters": [{"field":"status","op":"EQ","value":"FAILED"}],
                    "timeRange": {"kind":"RELATIVE","value":"YESTERDAY","timezone":"America/Chicago"},
                    "sort": [{"field":"created_at","direction":"DESC"}],
                    "limit": 50
                  },
                  "confidence": 0.87,
                  "needsClarification": false,
                  "clarificationQuestion": null,
                  "ambiguities": []
                }
                ```
                """;

        SemanticInterpretRequest request = new SemanticInterpretRequest(
                "show failed requests yesterday",
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("timezone", "America/Chicago")
        );

        SemanticInterpretResponse response = service.interpret(request, session(request.question()));

        assertNotNull(response);
        assertEquals("FAILED", String.valueOf(response.canonicalIntent().filters().getFirst().value()));
        assertTrue(response.meta().confidence() >= 0.80d || response.meta().needsClarification());
        assertTrue(response.meta().confidence() >= 0.0d);
    }

    @Test
    void fallbackParserAddsClarificationWhenConfidenceLow() {
        llmClient.response = "not-json-response";

        String question = "show requests for ups";
        EngineSession session = session(question);
        SemanticInterpretRequest request = new SemanticInterpretRequest(
                question,
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("timezone", "America/Chicago")
        );

        SemanticInterpretResponse response = service.interpret(request, session);

        assertNotNull(response);
        assertEquals("REQUEST", response.canonicalIntent().entity());
        assertTrue(response.meta().needsClarification());
        assertNotNull(response.meta().clarificationQuestion());
        assertTrue(response.meta().confidence() < 0.80d);
        assertEquals(response.meta().clarificationQuestion(), session.getPendingClarificationQuestion());
        assertEquals("SEMANTIC_QUERY_AMBIGUITY", session.getPendingClarificationReason());
    }

    @Test
    void strictSchemaSentToLlmHasClosedRootAdditionalProperties() throws Exception {
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
                  "ambiguities": [],
                  "trace": {"normalizations":[]}
                }
                """;

        String question = "show requests for ups";
        SemanticInterpretRequest request = new SemanticInterpretRequest(
                question,
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("timezone", "UTC")
        );

        service.interpret(request, session(question));

        assertNotNull(llmClient.lastJsonSchema);
        ObjectMapper mapper = new ObjectMapper();
        var schemaNode = mapper.readTree(llmClient.lastJsonSchema);
        assertFalse(schemaNode.path("additionalProperties").asBoolean(true));
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
        private String lastJsonSchema;

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
            this.lastJsonSchema = jsonSchema;
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
