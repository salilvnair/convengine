package com.github.salilvnair.convengine.engine.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.service.ConversationCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.DUPLICATE;
import static com.github.salilvnair.convengine.support.TestConstants.EMPTY_JSON;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_CONFIRMATION;
import static com.github.salilvnair.convengine.support.TestConstants.UNKNOWN;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineSessionFactoryTest {

    @Mock
    private ConversationCacheService cacheService;

    private EngineSessionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new EngineSessionFactory(new ObjectMapper(), cacheService);
    }

    @Test
    void openUsesCachedConversationWhenPresent() {
        UUID conversationId = UUID.randomUUID();
        CeConversation conversation = CeConversation.builder()
                .conversationId(conversationId)
                .intentCode(INTENT_LOAN_APPLICATION)
                .stateCode(STATE_CONFIRMATION)
                .contextJson(EMPTY_JSON)
                .inputParamsJson(EMPTY_JSON)
                .build();
        when(cacheService.getConversation(conversationId)).thenReturn(Optional.of(conversation));

        EngineSession session = factory.open(EngineContext.builder()
                .conversationId(conversationId.toString())
                .userText(USER_TEXT_HELLO)
                .build());

        assertSame(conversation, session.getConversation());
        assertEquals(INTENT_LOAN_APPLICATION, session.getIntent());
        assertEquals(STATE_CONFIRMATION, session.getState());
    }

    @Test
    void openCreatesConversationWhenCacheMisses() {
        UUID conversationId = UUID.randomUUID();
        when(cacheService.getConversation(conversationId)).thenReturn(Optional.empty());
        when(cacheService.createAndCacheSync(any(CeConversation.class))).thenAnswer(invocation -> {
            CeConversation created = invocation.getArgument(0);
            created.setIntentCode(UNKNOWN);
            created.setStateCode(UNKNOWN);
            return created;
        });

        EngineSession session = factory.open(EngineContext.builder()
                .conversationId(conversationId.toString())
                .userText(USER_TEXT_HELLO)
                .build());

        assertEquals(conversationId, session.getConversation().getConversationId());
        assertEquals(UNKNOWN, session.getIntent());
        assertEquals(UNKNOWN, session.getState());
    }

    @Test
    void openRecoversFromConcurrentCreateRace() {
        UUID conversationId = UUID.randomUUID();
        CeConversation existing = CeConversation.builder()
                .conversationId(conversationId)
                .intentCode(UNKNOWN)
                .stateCode(UNKNOWN)
                .contextJson(EMPTY_JSON)
                .inputParamsJson(EMPTY_JSON)
                .build();
        when(cacheService.getConversation(conversationId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(cacheService.createAndCacheSync(any(CeConversation.class)))
                .thenThrow(new DataIntegrityViolationException(DUPLICATE));

        EngineSession session = factory.open(EngineContext.builder()
                .conversationId(conversationId.toString())
                .userText(USER_TEXT_HELLO)
                .build());

        assertSame(existing, session.getConversation());
    }
}
