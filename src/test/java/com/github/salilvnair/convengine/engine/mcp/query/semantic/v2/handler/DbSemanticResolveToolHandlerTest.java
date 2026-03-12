package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelDynamicOverlayService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelLoader;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticResolveMappingValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticResolveService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbSemanticResolveToolHandlerTest {

    private DbSemanticResolveToolHandler handler;

    @BeforeEach
    void setUp() {
        ConvEngineMcpConfig config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setEnabled(true);

        SemanticModelLoader loader = new SemanticModelLoader(config, new DefaultResourceLoader());
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> noJdbc = factory.getBeanProvider(NamedParameterJdbcTemplate.class);
        SemanticModelRegistry registry = new SemanticModelRegistry(loader, new SemanticModelDynamicOverlayService(noJdbc), new SemanticModelValidator());
        registry.setModel(new SemanticModel(1, "db", "desc", Map.of(), java.util.List.of(), Map.of(), Map.of(), Map.of()));

        SemanticResolveService service = new SemanticResolveService(
                config,
                registry,
                new SemanticResolveMappingValidator(),
                noJdbc,
                new NoopAuditService(),
                null
        );
        handler = new DbSemanticResolveToolHandler(service);
    }

    @Test
    void parsesCanonicalIntentFromMapArg() {
        EngineSession session = session("show failed requests");
        Map<String, Object> args = Map.of(
                "canonicalIntent", Map.of(
                        "intent", "LIST_REQUESTS",
                        "entity", "REQUEST",
                        "queryClass", "LIST_REQUESTS",
                        "filters", java.util.List.of(Map.of("field", "status", "op", "EQ", "value", "FAILED"))
                )
        );

        Object out = handler.execute(null, args, session);
        SemanticResolveResponse response = (SemanticResolveResponse) out;

        assertNotNull(response);
        assertNotNull(response.meta());
        assertEquals("db.semantic.resolve", response.meta().tool());
    }

    @Test
    void handlesMissingCanonicalIntentWithoutCrash() {
        EngineSession session = session("show failed requests");
        Object out = handler.execute(null, Map.of(), session);

        SemanticResolveResponse response = (SemanticResolveResponse) out;
        assertNotNull(response);
        assertNotNull(response.unresolved());
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

    private static final class NoopAuditService implements AuditService {
        @Override
        public void audit(String stage, UUID conversationId, String payloadJson) {
            // no-op
        }
    }
}
