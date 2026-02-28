package com.github.salilvnair.convengine.engine.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.BOOM;
import static com.github.salilvnair.convengine.support.TestConstants.ERROR_MESSAGE_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.ERROR_TYPE_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.RESULT_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_PROCESS_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_ENTER;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_ERROR;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_MCP_TOOL;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_NAME_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_RULES;
import static com.github.salilvnair.convengine.support.TestConstants.TOOL_CODE_LOAN_SUBMIT;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VerboseStepHookTest {

    @Mock
    private VerboseMessagePublisher verboseMessagePublisher;

    private VerboseStepHook hook;

    @BeforeEach
    void setUp() {
        hook = new VerboseStepHook(verboseMessagePublisher);
    }

    @Test
    void beforeStepPublishesEnterVerbose() {
        EngineSession session = newSession();

        hook.beforeStep(STEP_MCP_TOOL, session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(verboseMessagePublisher).publish(
                eq(session),
                eq(STEP_MCP_TOOL),
                eq(STEP_ENTER),
                eq(42L),
                eq(TOOL_CODE_LOAN_SUBMIT),
                eq(false),
                metadataCaptor.capture());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(STEP_NAME_KEY, STEP_MCP_TOOL);
        expected.put(INTENT_KEY, INTENT_LOAN_APPLICATION);
        expected.put(STATE_KEY, STATE_PROCESS_APPLICATION);
        expected.put(RESULT_KEY, null);
        assertEquals(expected, metadataCaptor.getValue());
    }

    @Test
    void onStepErrorPublishesErrorVerboseWithExceptionMetadata() {
        EngineSession session = newSession();

        hook.onStepError(STEP_RULES, session, new IllegalStateException(BOOM));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(verboseMessagePublisher).publish(
                eq(session),
                eq(STEP_RULES),
                eq(STEP_ERROR),
                eq(42L),
                eq(TOOL_CODE_LOAN_SUBMIT),
                eq(true),
                metadataCaptor.capture());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(STEP_NAME_KEY, STEP_RULES);
        expected.put(INTENT_KEY, INTENT_LOAN_APPLICATION);
        expected.put(STATE_KEY, STATE_PROCESS_APPLICATION);
        expected.put(RESULT_KEY, null);
        expected.put(ERROR_TYPE_KEY, IllegalStateException.class.getSimpleName());
        expected.put(ERROR_MESSAGE_KEY, BOOM);
        assertEquals(expected, metadataCaptor.getValue());
    }

    private EngineSession newSession() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
        session.setIntent(INTENT_LOAN_APPLICATION);
        session.setState(STATE_PROCESS_APPLICATION);
        session.putInputParam("rule_id", 42L);
        session.putInputParam("mcp_tool_code", TOOL_CODE_LOAN_SUBMIT);
        return session;
    }
}
