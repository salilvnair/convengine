package com.github.salilvnair.convengine.transport.verbose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.constants.ProcessingStatusConstants;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.resolve.MessageResolverFactory;
import com.github.salilvnair.convengine.transport.verbose.resolve.VerboseResolveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.EVENT_PRECHECK_STARTED;
import static com.github.salilvnair.convengine.support.TestConstants.HOOK_NAME;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.PHASE;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_PROCESS_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.TOOL_CODE_LOAN_SUBMIT;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerboseMessagePublisherTest {

    @Mock
    private MessageResolverFactory resolverFactory;

    @Mock
    private VerboseEventDispatcher dispatcher;

    private VerboseMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new VerboseMessagePublisher(resolverFactory, dispatcher);
    }

    @Test
    void publishesFallbackErrorWhenNoResolverMatches() {
        EngineSession session = newSession();
        when(resolverFactory.resolve(any(VerboseResolveRequest.class))).thenReturn(Optional.empty());

        publisher.publish(
                session,
                HOOK_NAME,
                EVENT_PRECHECK_STARTED,
                7L,
                TOOL_CODE_LOAN_SUBMIT,
                true,
                Map.of(PHASE, "precheck"));

        ArgumentCaptor<VerboseStreamPayload> payloadCaptor = ArgumentCaptor.forClass(VerboseStreamPayload.class);
        verify(dispatcher).dispatch(eq(session.getConversationId()), payloadCaptor.capture());
        VerboseStreamPayload payload = payloadCaptor.getValue();

        assertEquals(HOOK_NAME, payload.getStepName());
        assertEquals(EVENT_PRECHECK_STARTED, payload.getDeterminant());
        assertEquals(ProcessingStatusConstants.ERROR, payload.getLevel());
        assertEquals("Something went wrong while processing " + HOOK_NAME + ".", payload.getText());
        assertEquals("precheck", payload.getMetadata().get(PHASE));
    }

    private EngineSession newSession() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
        session.setIntent(INTENT_LOAN_APPLICATION);
        session.setState(STATE_PROCESS_APPLICATION);
        return session;
    }
}
