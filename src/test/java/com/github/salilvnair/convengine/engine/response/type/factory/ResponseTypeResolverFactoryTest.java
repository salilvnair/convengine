package com.github.salilvnair.convengine.engine.response.type.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
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
class ResponseTypeResolverFactoryTest {

    @Mock
    private VerboseMessagePublisher verboseMessagePublisher;

    private ResponseTypeResolverFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ResponseTypeResolverFactory(List.of(new StubResponseTypeResolver()), verboseMessagePublisher);
    }

    @Test
    void getReturnsMatchingResolverIgnoringCase() {
        ResponseTypeResolver resolver = factory.get("derived");

        assertSame(StubResponseTypeResolver.class, resolver.getClass());
    }

    @Test
    void getThrowsForUnknownType() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText("hello")
                .build(), new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> factory.get("exact", session));
    }

    private static final class StubResponseTypeResolver implements ResponseTypeResolver {
        @Override
        public String type() {
            return "DERIVED";
        }

        @Override
        public void resolve(EngineSession session, PromptTemplate template, ResponseTemplate response) {
        }
    }
}
