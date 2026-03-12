package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.SchemaEdge;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticClarificationStageTest {

    private ConvEngineMcpConfig config;
    private SemanticClarificationStage stage;

    @BeforeEach
    void setUp() {
        config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setEnabled(true);
        config.getDb().getSemantic().setToolCode("db.semantic.query");
        config.getDb().getSemantic().getClarification().setEnabled(true);
        config.getDb().getSemantic().getClarification().setConfidenceThreshold(0.80d);
        config.getDb().getSemantic().getClarification().setMinTopEntityGap(0.12d);

        stage = new SemanticClarificationStage(config, new NoopAuditService(), null);
    }

    @Test
    void requiresClarificationWhenTopEntitiesAreAmbiguous() {
        EngineSession session = session("show requests for ups");
        SemanticQueryContext context = new SemanticQueryContext("show requests for ups", session);
        context.retrieval(new RetrievalResult(
                context.question(),
                List.of(
                        new CandidateEntity("REQUEST", 0.71d, 0.71d, 0.0d, List.of()),
                        new CandidateEntity("ACCOUNT", 0.69d, 0.69d, 0.0d, List.of())
                ),
                List.of(),
                "HIGH"
        ));
        context.joinPath(new JoinPathPlan("zp_disco_request", List.of(), List.of("zp_disco_request"), List.of(), 1.0d));

        stage.execute(context);

        assertTrue(context.clarificationRequired());
        assertNotNull(context.clarificationQuestion());
        assertTrue(context.clarificationQuestion().contains("REQUEST") || context.clarificationQuestion().contains("ACCOUNT"));
        assertNotNull(context.clarificationDiagnostics());
        assertTrue(Boolean.TRUE.equals(context.clarificationDiagnostics().get("ambiguousTopEntities")));
        assertTrue(session.hasPendingClarification());
        assertTrue(session.isAwaitingClarification());
    }

    @Test
    void skipsClarificationWhenConfidenceHighAndNoAmbiguity() {
        EngineSession session = session("show failed disconnect requests");
        SemanticQueryContext context = new SemanticQueryContext("show failed disconnect requests", session);
        context.retrieval(new RetrievalResult(
                context.question(),
                List.of(new CandidateEntity("REQUEST", 0.95d, 0.95d, 0.0d, List.of())),
                List.of(),
                "HIGH"
        ));
        context.joinPath(new JoinPathPlan(
                "zp_disco_request",
                List.of(new SchemaEdge("zp_disco_request", "request_id", "zp_disco_trans_data", "request_id", "FK", "LEFT")),
                List.of("zp_disco_request", "zp_disco_trans_data"),
                List.of(),
                1.0d
        ));

        stage.execute(context);

        assertFalse(context.clarificationRequired());
        assertFalse(session.hasPendingClarification());
        assertFalse(session.isAwaitingClarification());
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
