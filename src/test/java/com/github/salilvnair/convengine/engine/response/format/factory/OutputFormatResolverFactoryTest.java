package com.github.salilvnair.convengine.engine.response.format.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class OutputFormatResolverFactoryTest {

    @Mock
    private VerboseMessagePublisher verboseMessagePublisher;

    private OutputFormatResolverFactory factory;

    @BeforeEach
    void setUp() {
        factory = new OutputFormatResolverFactory(List.of(new StubOutputFormatResolver()), verboseMessagePublisher);
    }

    @Test
    void getReturnsMatchingResolverIgnoringCase() {
        OutputFormatResolver resolver = factory.get("text");

        assertSame(StubOutputFormatResolver.class, resolver.getClass());
    }

    @Test
    void getThrowsForUnknownFormat() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText("hello")
                .build(), new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> factory.get("json", session));
    }

    private static final class StubOutputFormatResolver implements OutputFormatResolver {
        @Override
        public String format() {
            return "TEXT";
        }

        @Override
        public void resolve(EngineSession session, ResponseTemplate response, PromptTemplate template) {
        }
    }
}
