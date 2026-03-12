package com.github.salilvnair.convengine.engine.mcp.executor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.McpSqlGuardrail;
import com.github.salilvnair.convengine.engine.mcp.executor.interceptor.PostgresQueryInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureRecord;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresQueryToolHandlerTest {

    @Test
    void executesWithNamedParamsFromArgs() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        McpSqlGuardrail guardrail = mock(McpSqlGuardrail.class);
        AuditService auditService = mock(AuditService.class);
        VerboseMessagePublisher verbosePublisher = mock(VerboseMessagePublisher.class);
        SemanticFailureFeedbackService failureService = mock(SemanticFailureFeedbackService.class);
        doNothing().when(guardrail).assertReadOnly(anyString(), anyString());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", "ZPR123");
        when(jdbcTemplate.queryForList(anyString(), anyMap())).thenReturn(List.of(row));

        PostgresQueryToolHandler handler = new PostgresQueryToolHandler(
                jdbcTemplate,
                guardrail,
                auditService,
                verbosePublisher,
                emptyInterceptorProvider(),
                failureService
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "SELECT request_id FROM zp_disco_request WHERE request_id=:id");
        args.put("params", Map.of("id", "ZPR123"));

        Object out = handler.execute(null, args, session("show request ZPR123"));
        Map<?, ?> payload = (Map<?, ?>) out;
        assertEquals("SUCCESS", payload.get("status"));
        verify(jdbcTemplate).queryForList("SELECT request_id FROM zp_disco_request WHERE request_id=:id", Map.of("id", "ZPR123"));
    }

    @Test
    void recordsFailureWhenExecutionErrors() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        McpSqlGuardrail guardrail = mock(McpSqlGuardrail.class);
        AuditService auditService = mock(AuditService.class);
        VerboseMessagePublisher verbosePublisher = mock(VerboseMessagePublisher.class);
        SemanticFailureFeedbackService failureService = mock(SemanticFailureFeedbackService.class);
        doNothing().when(guardrail).assertReadOnly(anyString(), anyString());
        when(jdbcTemplate.queryForList(anyString(), anyMap())).thenThrow(new RuntimeException("syntax error"));

        PostgresQueryToolHandler handler = new PostgresQueryToolHandler(
                jdbcTemplate,
                guardrail,
                auditService,
                verbosePublisher,
                emptyInterceptorProvider(),
                failureService
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "SELECT * FROM zp_disco_request");

        assertThrows(IllegalArgumentException.class, () -> handler.execute(null, args, session("show requests")));
        verify(failureService).recordFailure(any(SemanticFailureRecord.class));
    }

    @Test
    void executesWhenPlannerSendsSqlArgAlias() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        McpSqlGuardrail guardrail = mock(McpSqlGuardrail.class);
        AuditService auditService = mock(AuditService.class);
        VerboseMessagePublisher verbosePublisher = mock(VerboseMessagePublisher.class);
        SemanticFailureFeedbackService failureService = mock(SemanticFailureFeedbackService.class);
        doNothing().when(guardrail).assertReadOnly(anyString(), anyString());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", "REQ-20260311-0001");
        when(jdbcTemplate.queryForList(anyString(), anyMap())).thenReturn(List.of(row));

        PostgresQueryToolHandler handler = new PostgresQueryToolHandler(
                jdbcTemplate,
                guardrail,
                auditService,
                verbosePublisher,
                emptyInterceptorProvider(),
                failureService
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", "SELECT request_id FROM zp_disco_request WHERE customer_name=:customer");
        args.put("params", Map.of("customer", "UPS"));

        Object out = handler.execute(null, args, session("show disconnect requests for ups"));
        Map<?, ?> payload = (Map<?, ?>) out;
        assertEquals("SUCCESS", payload.get("status"));
        assertEquals("SELECT request_id FROM zp_disco_request WHERE customer_name=:customer", args.get("query"));
        verify(jdbcTemplate).queryForList(
                "SELECT request_id FROM zp_disco_request WHERE customer_name=:customer",
                Map.of("customer", "UPS")
        );
    }

    private EngineSession session(String userText) {
        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText)
                .inputParams(new LinkedHashMap<>())
                .build();
        return new EngineSession(context, new ObjectMapper());
    }

    private ObjectProvider<List<PostgresQueryInterceptor>> emptyInterceptorProvider() {
        List<PostgresQueryInterceptor> interceptors = List.of();
        return new ObjectProvider<>() {
            @Override
            public List<PostgresQueryInterceptor> getObject(Object... args) {
                return interceptors;
            }

            @Override
            public List<PostgresQueryInterceptor> getIfAvailable() {
                return interceptors;
            }

            @Override
            public List<PostgresQueryInterceptor> getIfUnique() {
                return interceptors;
            }

            @Override
            public List<PostgresQueryInterceptor> getObject() {
                return interceptors;
            }

            @Override
            public java.util.Iterator<List<PostgresQueryInterceptor>> iterator() {
                return List.of(interceptors).iterator();
            }
        };
    }
}
