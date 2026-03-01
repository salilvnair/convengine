package com.github.salilvnair.convengine.engine.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.EMPTY_JSON;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_COMPLETED;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnginePipelineTest {

    @Test
    void executeReturnsStopResultImmediately() {
        EngineResult expected = new EngineResult(INTENT_LOAN_APPLICATION, STATE_COMPLETED, null, EMPTY_JSON);
        EnginePipeline pipeline = new EnginePipeline(List.of(
                session -> new StepResult.Stop(expected),
                session -> {
                    throw new AssertionError("Should not execute next step");
                }
        ));

        EngineResult actual = pipeline.execute(newSession());

        assertEquals(expected, actual);
    }

    @Test
    void executeReturnsFinalSessionResultWhenStepsContinue() {
        EngineSession session = newSession();
        EngineResult expected = new EngineResult(INTENT_LOAN_APPLICATION, STATE_COMPLETED, null, EMPTY_JSON);
        session.setFinalResult(expected);
        EnginePipeline pipeline = new EnginePipeline(List.of(
                ignored -> new StepResult.Continue(),
                ignored -> new StepResult.Continue()
        ));

        EngineResult actual = pipeline.execute(session);

        assertEquals(expected, actual);
    }

    @Test
    void executeThrowsWhenNoFinalResultWasProduced() {
        EnginePipeline pipeline = new EnginePipeline(List.of(ignored -> new StepResult.Continue()));

        assertThrows(ConversationEngineException.class, () -> pipeline.execute(newSession()));
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
    }
}
