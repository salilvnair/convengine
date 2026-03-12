package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.entity.CeSemanticQueryFailure;
import com.github.salilvnair.convengine.repo.SemanticQueryFailureRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticFailureFeedbackServiceTest {

    @Mock
    private SemanticQueryFailureRepository repository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private SemanticFailureFeedbackService service;

    @Test
    void recordsFailureAndAudits() {
        UUID conversationId = UUID.randomUUID();
        when(repository.save(any(CeSemanticQueryFailure.class))).thenAnswer(invocation -> {
            CeSemanticQueryFailure entity = invocation.getArgument(0);
            entity.setId(42L);
            return entity;
        });

        service.recordFailure(new SemanticFailureRecord(
                conversationId,
                "show failed disconnects",
                "SELECT * FROM zp_disco_request",
                "SELECT request_id FROM zp_disco_request",
                "WRONG_SELECT",
                "selected wildcard",
                "SEMANTIC_QUERY_V2",
                Map.of("source", "test")
        ));

        ArgumentCaptor<CeSemanticQueryFailure> entityCaptor = ArgumentCaptor.forClass(CeSemanticQueryFailure.class);
        verify(repository).save(entityCaptor.capture());
        CeSemanticQueryFailure saved = entityCaptor.getValue();
        assertEquals(conversationId, saved.getConversationId());
        assertEquals("show failed disconnects", saved.getQuestion());
        assertEquals("WRONG_SELECT", saved.getRootCause());
        assertEquals("SEMANTIC_QUERY_V2", saved.getStage());
        verify(auditService).audit(any(String.class), any(UUID.class), any(Map.class));
    }

    @Test
    void skipsWhenQuestionMissing() {
        service.recordFailure(new SemanticFailureRecord(
                UUID.randomUUID(),
                "   ",
                "SELECT 1",
                null,
                "X",
                "Y",
                "Z",
                Map.of()
        ));
        verify(repository, never()).save(any(CeSemanticQueryFailure.class));
        verify(auditService, never()).audit(any(String.class), any(UUID.class), any(Map.class));
    }

    @Test
    void swallowsPersistenceException() {
        when(repository.save(any(CeSemanticQueryFailure.class))).thenThrow(new RuntimeException("db down"));
        service.recordFailure(new SemanticFailureRecord(
                UUID.randomUUID(),
                "show requests",
                null,
                null,
                "DB_DOWN",
                "db down",
                "POSTGRES_QUERY_EXECUTION",
                null
        ));
        verify(repository).save(any(CeSemanticQueryFailure.class));
        verify(auditService, never()).audit(any(String.class), any(UUID.class), any(Map.class));
    }
}
