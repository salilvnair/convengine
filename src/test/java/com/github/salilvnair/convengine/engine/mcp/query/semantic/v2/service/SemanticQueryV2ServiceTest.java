package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedJoinPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSelectItem;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSemanticPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedTimeRange;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryRequestV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryResponseV2;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SemanticQueryV2ServiceTest {

    @Test
    void compilesSqlFromResolvedPlan() {
        SemanticQueryV2Service service = new SemanticQueryV2Service(
                providerOf(List.of()),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );

        ResolvedSemanticPlan plan = new ResolvedSemanticPlan(
                "LIST_REQUESTS",
                "REQUEST",
                "zp_disco_request",
                List.of(
                        new ResolvedSelectItem("requestId", "zp_disco_request.request_id"),
                        new ResolvedSelectItem("status", "zp_disco_request.request_status")
                ),
                List.of(new ResolvedFilter("status", "zp_disco_request.request_status", "EQ", "FAILED")),
                List.of(new ResolvedJoinPlan("zp_disco_request", "zp_disco_trans_data", "LEFT", "zp_disco_request.request_id = zp_disco_trans_data.request_id")),
                new ResolvedTimeRange("zp_disco_request.created_at", "2026-03-01", "2026-03-11", "America/Chicago"),
                List.of(new ResolvedSort("zp_disco_request.created_at", "DESC")),
                100
        );

        SemanticQueryResponseV2 response = service.query(
                new SemanticQueryRequestV2(plan, true, false, UUID.randomUUID().toString()),
                session("show failed requests")
        );

        assertNotNull(response);
        assertNotNull(response.compiledSql());
        assertTrue(response.guardrail().allowed());
        assertFalse(response.meta().needsClarification());
        String sql = response.compiledSql().sql();
        assertTrue(sql.contains("SELECT zp_disco_request.request_id, zp_disco_request.request_status"));
        assertTrue(sql.contains("FROM zp_disco_request"));
        assertTrue(sql.contains("LEFT JOIN zp_disco_trans_data"));
        assertTrue(sql.contains("zp_disco_request.request_status = :p_f_1"));
        assertTrue(sql.contains("zp_disco_request.created_at >= :p_time_from"));
        assertTrue(sql.contains("ORDER BY zp_disco_request.created_at DESC"));
        assertTrue(sql.contains("LIMIT :p_limit"));
        assertEquals("FAILED", response.compiledSql().params().get("p_f_1"));
    }

    @Test
    void strictModeFailsForIncompletePlan() {
        SemanticQueryV2Service service = new SemanticQueryV2Service(
                providerOf(List.of()),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );
        ResolvedSemanticPlan plan = new ResolvedSemanticPlan(
                "LIST_REQUESTS",
                "REQUEST",
                "zp_disco_request",
                List.of(new ResolvedSelectItem("requestId", null)),
                List.of(),
                List.of(),
                null,
                List.of(),
                100
        );

        try {
            service.query(new SemanticQueryRequestV2(plan, true, false, UUID.randomUUID().toString()), session("x"));
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("strictMode=true"));
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException for strict mode validation failure");
    }

    @Test
    void returnsGuardrailBlockedWhenValidatorFails() {
        SemanticSqlPolicyValidator validator = (compiledSql, context) -> {
            throw new IllegalStateException("blocked by policy");
        };
        SemanticQueryV2Service service = new SemanticQueryV2Service(
                providerOf(List.of(validator)),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                mock(SemanticFailureFeedbackService.class)
        );

        ResolvedSemanticPlan plan = new ResolvedSemanticPlan(
                "LIST_REQUESTS",
                "REQUEST",
                "zp_disco_request",
                List.of(new ResolvedSelectItem("requestId", "zp_disco_request.request_id")),
                List.of(),
                List.of(),
                null,
                List.of(),
                10
        );

        SemanticQueryResponseV2 response = service.query(
                new SemanticQueryRequestV2(plan, false, false, UUID.randomUUID().toString()),
                session("show requests")
        );

        assertFalse(response.guardrail().allowed());
        assertTrue(response.guardrail().reason().contains("blocked by policy"));
    }

    @Test
    void recordsFailureWhenGuardrailDenies() {
        SemanticSqlPolicyValidator validator = (compiledSql, context) -> {
            throw new IllegalStateException("blocked by policy");
        };
        SemanticFailureFeedbackService failureService = mock(SemanticFailureFeedbackService.class);
        SemanticQueryV2Service service = new SemanticQueryV2Service(
                providerOf(List.of(validator)),
                mock(AuditService.class),
                mock(VerboseMessagePublisher.class),
                failureService
        );

        ResolvedSemanticPlan plan = new ResolvedSemanticPlan(
                "LIST_REQUESTS",
                "REQUEST",
                "zp_disco_request",
                List.of(new ResolvedSelectItem("requestId", "zp_disco_request.request_id")),
                List.of(),
                List.of(),
                null,
                List.of(),
                10
        );

        service.query(new SemanticQueryRequestV2(plan, false, false, UUID.randomUUID().toString()), session("show requests"));
        verify(failureService).recordFailure(org.mockito.ArgumentMatchers.any());
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

    private ObjectProvider<List<SemanticSqlPolicyValidator>> providerOf(List<SemanticSqlPolicyValidator> validators) {
        return new ObjectProvider<>() {
            @Override
            public List<SemanticSqlPolicyValidator> getObject(Object... args) {
                return validators;
            }

            @Override
            public List<SemanticSqlPolicyValidator> getIfAvailable() {
                return validators;
            }

            @Override
            public List<SemanticSqlPolicyValidator> getIfUnique() {
                return validators;
            }

            @Override
            public List<SemanticSqlPolicyValidator> getObject() {
                return validators;
            }

            @Override
            public java.util.Iterator<List<SemanticSqlPolicyValidator>> iterator() {
                return List.of(validators).iterator();
            }
        };
    }
}
