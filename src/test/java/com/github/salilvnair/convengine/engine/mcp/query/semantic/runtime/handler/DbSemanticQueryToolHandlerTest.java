package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryRuntimeService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryResponseV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticLlmQueryService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticQueryV2Service;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DbSemanticQueryToolHandlerTest {

    @Test
    void usesLegacyRuntimeWhenResolvedPlanAbsent() {
        ConvEngineMcpConfig config = baseConfig(false);
        StubRuntimeService runtimeService = new StubRuntimeService(config);
        SemanticQueryV2Service queryV2Service = new SemanticQueryV2Service(
                emptyValidatorProvider(),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );
        DbSemanticQueryToolHandler handler = new DbSemanticQueryToolHandler(
                config,
                runtimeService,
                queryV2Service,
                mock(SemanticLlmQueryService.class)
        );

        Object out = handler.execute(null, Map.of("question", "show requests"), session("show requests"));

        assertTrue(out instanceof Map<?, ?>);
        assertEquals("legacy", ((Map<?, ?>) out).get("mode"));
        assertEquals("show requests", runtimeService.lastQuestion);
    }

    @Test
    void usesV2WhenResolvedPlanProvided() {
        ConvEngineMcpConfig config = baseConfig(false);
        config.getDb().getSemantic().setQueryMode("deterministic");
        StubRuntimeService runtimeService = new StubRuntimeService(config);
        SemanticQueryV2Service queryV2Service = new SemanticQueryV2Service(
                emptyValidatorProvider(),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );
        DbSemanticQueryToolHandler handler = new DbSemanticQueryToolHandler(
                config,
                runtimeService,
                queryV2Service,
                mock(SemanticLlmQueryService.class)
        );

        Map<String, Object> args = Map.of(
                "resolvedPlan", Map.of(
                        "queryClass", "LIST_REQUESTS",
                        "baseEntity", "REQUEST",
                        "baseTable", "zp_disco_request",
                        "select", List.of(Map.of("field", "requestId", "column", "zp_disco_request.request_id")),
                        "filters", List.of(),
                        "joins", List.of(),
                        "sort", List.of(),
                        "limit", 10
                )
        );

        Object out = handler.execute(null, args, session("show requests"));

        assertTrue(out instanceof SemanticQueryResponseV2);
        SemanticQueryResponseV2 response = (SemanticQueryResponseV2) out;
        assertNotNull(response.compiledSql());
        assertTrue(response.compiledSql().sql().contains("FROM zp_disco_request"));
        assertEquals("", runtimeService.lastQuestion);
    }

    @Test
    void strictModeToggleFromConfigAppliesToV2Path() {
        ConvEngineMcpConfig config = baseConfig(true);
        config.getDb().getSemantic().setQueryMode("deterministic");
        StubRuntimeService runtimeService = new StubRuntimeService(config);
        SemanticQueryV2Service queryV2Service = new SemanticQueryV2Service(
                emptyValidatorProvider(),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );
        DbSemanticQueryToolHandler handler = new DbSemanticQueryToolHandler(
                config,
                runtimeService,
                queryV2Service,
                mock(SemanticLlmQueryService.class)
        );

        Map<String, Object> args = Map.of(
                "resolvedPlan", Map.of(
                        "queryClass", "LIST_REQUESTS",
                        "baseEntity", "REQUEST",
                        "baseTable", "zp_disco_request",
                        "select", List.of(Map.of("field", "requestId", "column", "")),
                        "filters", List.of(),
                        "joins", List.of(),
                        "sort", List.of(),
                        "limit", 10
                )
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(null, args, session("show requests")));
        assertTrue(ex.getMessage().contains("strictMode=true"));
    }

    private ConvEngineMcpConfig baseConfig(boolean strictMode) {
        ConvEngineMcpConfig config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setEnabled(true);
        config.getDb().getSemantic().setToolCode("db.semantic.query");
        config.getDb().getSemantic().setStrictMode(strictMode);
        return config;
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

    private ObjectProvider<List<SemanticSqlPolicyValidator>> emptyValidatorProvider() {
        return new ObjectProvider<>() {
            @Override
            public List<SemanticSqlPolicyValidator> getObject(Object... args) {
                return List.of();
            }

            @Override
            public List<SemanticSqlPolicyValidator> getIfAvailable() {
                return List.of();
            }

            @Override
            public List<SemanticSqlPolicyValidator> getIfUnique() {
                return List.of();
            }

            @Override
            public List<SemanticSqlPolicyValidator> getObject() {
                return List.of();
            }

            @Override
            public java.util.Iterator<List<SemanticSqlPolicyValidator>> iterator() {
                return List.<List<SemanticSqlPolicyValidator>>of(List.of()).iterator();
            }
        };
    }

    private static final class StubRuntimeService extends SemanticQueryRuntimeService {
        private String lastQuestion = "";

        private StubRuntimeService(ConvEngineMcpConfig config) {
            super(config, null, null, null, null, null);
        }

        @Override
        public Map<String, Object> plan(String question, EngineSession session) {
            this.lastQuestion = question == null ? "" : question;
            return Map.of("mode", "legacy", "question", this.lastQuestion);
        }
    }
}
