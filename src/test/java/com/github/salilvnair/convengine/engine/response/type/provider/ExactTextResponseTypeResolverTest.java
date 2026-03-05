package com.github.salilvnair.convengine.engine.response.type.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExactTextResponseTypeResolverTest {

    @Test
    void exactResponseRendersThymeleafUsingSessionAndMetadata() {
        AuditService auditService = mock(AuditService.class);
        ExactTextResponseTypeResolver resolver =
                new ExactTextResponseTypeResolver(auditService, new ThymeleafTemplateRenderer());

        UUID conversationId = UUID.randomUUID();
        EngineSession session = new EngineSession(
                EngineContext.builder()
                        .conversationId(conversationId.toString())
                        .userText("hello")
                        .inputParams(Map.of("name", "Salil"))
                        .build(),
                new ObjectMapper()
        );
        session.setIntent("SEMANTIC_QUERY");
        session.setState("ANALYZE");
        session.setConversation(CeConversation.builder()
                .conversationId(conversationId)
                .intentCode("SEMANTIC_QUERY")
                .stateCode("ANALYZE")
                .build());

        ResponseTemplate response = ResponseTemplate.builder()
                .outputFormat("TEXT")
                .responseType("EXACT")
                .exactText("Hi [(${name})], intent=[(${intent})], state=[(${state})], type=[(${responseType})]")
                .build();

        resolver.resolve(
                session,
                PromptTemplate.builder().templateId(999L).responseType("EXACT").build(),
                response
        );

        assertNotNull(session.getPayload());
        assertEquals(
                "Hi Salil, intent=SEMANTIC_QUERY, state=ANALYZE, type=EXACT",
                ((TextPayload) session.getPayload()).text()
        );
        assertEquals("RESPONSE_EXACT", session.getLastLlmStage());
        verify(auditService).audit(eq(ConvEngineAuditStage.RESPONSE_EXACT), eq(conversationId), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void malformedTemplateFallsBackToMcpFinalAnswer() {
        AuditService auditService = mock(AuditService.class);
        ExactTextResponseTypeResolver resolver =
                new ExactTextResponseTypeResolver(auditService, new ThymeleafTemplateRenderer());

        UUID conversationId = UUID.randomUUID();
        EngineSession session = new EngineSession(
                EngineContext.builder()
                        .conversationId(conversationId.toString())
                        .userText("hello")
                        .inputParams(Map.of())
                        .build(),
                new ObjectMapper()
        );
        session.setContextJson("{\"mcp\":{\"finalAnswer\":\"Safe fallback final answer\"}}");
        session.setIntent("SEMANTIC_QUERY");
        session.setState("COMPLETED");
        session.setConversation(CeConversation.builder()
                .conversationId(conversationId)
                .intentCode("SEMANTIC_QUERY")
                .stateCode("COMPLETED")
                .build());

        ResponseTemplate response = ResponseTemplate.builder()
                .outputFormat("TEXT")
                .responseType("EXACT")
                .exactText("[# th:if=\"${badSyntax}\"][[$\"||'{oops}]][/]")
                .build();

        resolver.resolve(
                session,
                PromptTemplate.builder().templateId(1000L).responseType("EXACT").build(),
                response
        );

        assertNotNull(session.getPayload());
        assertEquals("Safe fallback final answer", ((TextPayload) session.getPayload()).text());
        verify(auditService).audit(eq(ConvEngineAuditStage.PROMPT_RENDERING), eq(conversationId), anyMap());
        verify(auditService).audit(eq(ConvEngineAuditStage.RESPONSE_EXACT), eq(conversationId), anyMap());
    }
}
