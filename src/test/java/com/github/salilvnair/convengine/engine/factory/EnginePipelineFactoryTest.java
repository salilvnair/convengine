package com.github.salilvnair.convengine.engine.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.pipeline.EnginePipeline;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.ConversationBootstrapStep;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.pipeline.annotation.TerminalStep;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.EMPTY_JSON;
import static com.github.salilvnair.convengine.support.TestConstants.INTENT_LOAN_APPLICATION;
import static com.github.salilvnair.convengine.support.TestConstants.STATE_COMPLETED;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EnginePipelineFactoryTest {

    @Test
    void initBuildsDagOrderedPipeline() {
        List<String> calls = new ArrayList<>();
        EnginePipelineFactory factory = new EnginePipelineFactory(
                List.of(
                        new FinalAnnotatedStep(calls),
                        new PersistedAnnotatedStep(calls),
                        new BootstrapAnnotatedStep(calls)
                ),
                List.of(),
                auditNoop());

        factory.init();
        EnginePipeline pipeline = factory.create();
        EngineResult result = pipeline.execute(newSession());

        assertEquals(List.of("bootstrap", "persisted", "terminal"), calls);
        assertEquals(STATE_COMPLETED, result.state());
    }

    @Test
    void initThrowsWhenNoTerminalStepExists() {
        EnginePipelineFactory factory = new EnginePipelineFactory(
                List.of(new BootstrapAnnotatedStep(new ArrayList<>())),
                List.of(),
                auditNoop());

        assertThrows(ConversationEngineException.class, factory::init);
    }

    private AuditService auditNoop() {
        return mock(AuditService.class);
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
    }

    @ConversationBootstrapStep
    private static final class BootstrapAnnotatedStep implements EngineStep {
        private final List<String> calls;

        private BootstrapAnnotatedStep(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public StepResult execute(EngineSession session) {
            calls.add("bootstrap");
            return new StepResult.Continue();
        }
    }

    @RequiresConversationPersisted
    @MustRunAfter(BootstrapAnnotatedStep.class)
    private static final class PersistedAnnotatedStep implements EngineStep {
        private final List<String> calls;

        private PersistedAnnotatedStep(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public StepResult execute(EngineSession session) {
            calls.add("persisted");
            return new StepResult.Continue();
        }
    }

    @TerminalStep
    @MustRunAfter(PersistedAnnotatedStep.class)
    private static final class FinalAnnotatedStep implements EngineStep {
        private final List<String> calls;

        private FinalAnnotatedStep(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public StepResult execute(EngineSession session) {
            calls.add("terminal");
            EngineResult result = new EngineResult(INTENT_LOAN_APPLICATION, STATE_COMPLETED, null, EMPTY_JSON);
            session.setFinalResult(result);
            return new StepResult.Stop(result);
        }
    }
}
