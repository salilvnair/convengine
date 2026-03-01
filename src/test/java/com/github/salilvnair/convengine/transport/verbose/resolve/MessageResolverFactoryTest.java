package com.github.salilvnair.convengine.transport.verbose.resolve;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageResolverFactoryTest {

    @Test
    void resolveReturnsFirstPresentPayload() {
        MessageResolverFactory factory = new MessageResolverFactory(List.of(
                request -> Optional.empty(),
                request -> Optional.of(VerboseStreamPayload.builder().text("matched").build()),
                request -> Optional.of(VerboseStreamPayload.builder().text("ignored").build())
        ));

        Optional<VerboseStreamPayload> resolved = factory.resolve(VerboseResolveRequest.builder().build());

        assertTrue(resolved.isPresent());
        assertEquals("matched", resolved.get().getText());
    }

    @Test
    void resolveReturnsEmptyForNullRequest() {
        MessageResolverFactory factory = new MessageResolverFactory(List.of(request -> Optional.empty()));

        assertTrue(factory.resolve(null).isEmpty());
    }
}
