package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackRequest;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.embedding.SemanticEmbeddingService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureRecord;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeMcpUserFeedback;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.repo.McpUserFeedbackRepository;
import com.github.salilvnair.convengine.repo.McpUserQueryKnowledgeRepository;
import com.github.salilvnair.convengine.repo.UserQueryKnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationFeedbackServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private McpUserFeedbackRepository feedbackRepository;
    @Mock
    private McpUserQueryKnowledgeRepository legacyUserQueryKnowledgeRepository;
    @Mock
    private UserQueryKnowledgeRepository userQueryKnowledgeRepository;
    @Mock
    private SemanticEmbeddingService semanticEmbeddingService;
    @Mock
    private SemanticFailureFeedbackService semanticFailureFeedbackService;
    @Mock
    private AuditService auditService;

    @Test
    void thumbsDownWithCorrectSqlWritesSemanticFailureRecord() {
        ConversationFeedbackService service = new ConversationFeedbackService(
                conversationRepository,
                feedbackRepository,
                legacyUserQueryKnowledgeRepository,
                userQueryKnowledgeRepository,
                semanticEmbeddingService,
                semanticFailureFeedbackService,
                auditService,
                new ObjectMapper()
        );

        UUID conversationId = UUID.randomUUID();
        CeConversation conversation = new CeConversation();
        conversation.setConversationId(conversationId);
        conversation.setIntentCode("SEMANTIC_QUERY");
        conversation.setStateCode("ANALYZE");
        conversation.setLastUserText("show failed disconnects");
        conversation.setContextJson("{\"mcp\":{\"observations\":[]}}");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(feedbackRepository.save(any(CeMcpUserFeedback.class))).thenAnswer(invocation -> {
            CeMcpUserFeedback saved = invocation.getArgument(0);
            saved.setFeedbackId(11L);
            return saved;
        });

        ConversationFeedbackRequest request = new ConversationFeedbackRequest();
        request.setConversationId(conversationId);
        request.setFeedbackType("THUMBS_DOWN");
        request.setMetadata(Map.of(
                "correct_sql", "SELECT request_id FROM zp_disco_request WHERE request_status='FAILED'",
                "wrong_sql", "SELECT * FROM zp_disco_request",
                "root_cause", "WRONG_SELECT"
        ));

        service.submit(request);

        ArgumentCaptor<SemanticFailureRecord> captor = ArgumentCaptor.forClass(SemanticFailureRecord.class);
        verify(semanticFailureFeedbackService).recordFailure(captor.capture());
        SemanticFailureRecord record = captor.getValue();
        assertNotNull(record);
        assertEquals(conversationId, record.conversationId());
        assertEquals("show failed disconnects", record.question());
        assertEquals("SELECT * FROM zp_disco_request", record.generatedSql());
        assertEquals("WRONG_SELECT", record.rootCause());
    }
}
