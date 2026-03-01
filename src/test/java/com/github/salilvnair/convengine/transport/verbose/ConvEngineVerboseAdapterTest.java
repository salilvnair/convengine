package com.github.salilvnair.convengine.transport.verbose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.EVENT_PRECHECK_STARTED;
import static com.github.salilvnair.convengine.support.TestConstants.EVENT_SUMMARY_READY;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.PHASE;
import static com.github.salilvnair.convengine.support.TestConstants.SOURCE;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_CONFIRMATION;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConvEngineVerboseAdapterTest {

    @Mock
    private VerboseMessagePublisher verboseMessagePublisher;

    @Mock
    private VerboseEventDispatcher verboseEventDispatcher;

    private ConvEngineVerboseAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ConvEngineVerboseAdapter(
                verboseMessagePublisher,
                verboseEventDispatcher,
                new ThymeleafTemplateRenderer());
    }

    @Test
    void publishResolvesObjectSourceToSimpleClassName() {
        EngineSession session = newSession();

        adapter.publish(session, this, EVENT_PRECHECK_STARTED, Map.of(PHASE, "before"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(verboseMessagePublisher).publish(
                eq(session),
                eq("ConvEngineVerboseAdapterTest"),
                eq(EVENT_PRECHECK_STARTED),
                eq(null),
                eq(null),
                eq(false),
                metadataCaptor.capture());
        assertEquals("before", metadataCaptor.getValue().get(PHASE));
        assertEquals("ConvEngineVerboseAdapterTest", metadataCaptor.getValue().get(SOURCE));
    }

    @Test
    void publishTextRendersThymeleafAgainstSessionAndMetadata() {
        EngineSession session = newSession();

        adapter.publishText(
                session,
                this,
                EVENT_SUMMARY_READY,
                "Intent [[${intent}]] for [[${source}]]",
                false,
                Map.of(PHASE, "confirmation"));

        ArgumentCaptor<VerboseStreamPayload> payloadCaptor = ArgumentCaptor.forClass(VerboseStreamPayload.class);
        verify(verboseEventDispatcher).dispatch(eq(session.getConversationId()), payloadCaptor.capture());
        VerboseStreamPayload payload = payloadCaptor.getValue();

        assertEquals("ConvEngineVerboseAdapterTest", payload.getStepName());
        assertEquals(EVENT_SUMMARY_READY, payload.getDeterminant());
        assertEquals(INTENT_LOAN_APPLICATION, payload.getIntent());
        assertEquals(STATE_CONFIRMATION, payload.getState());
        assertEquals("Intent " + INTENT_LOAN_APPLICATION + " for ConvEngineVerboseAdapterTest", payload.getText());
        assertEquals("progress", payload.getMetadata().get("theme"));
        assertEquals("confirmation", payload.getMetadata().get(PHASE));
        assertNull(payload.getErrorMessage());
    }

    private EngineSession newSession() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
        session.setIntent(INTENT_LOAN_APPLICATION);
        session.setState(STATE_CONFIRMATION);
        return session;
    }
}
