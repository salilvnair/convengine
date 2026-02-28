package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerboseEventDispatcherTest {

    @Test
    void dispatchContinuesWhenOneListenerThrows() {
        AtomicInteger calls = new AtomicInteger();
        VerboseEventListener failing = (conversationId, payload) -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        };
        VerboseEventListener succeeding = (conversationId, payload) -> calls.incrementAndGet();
        VerboseEventDispatcher dispatcher = new VerboseEventDispatcher(List.of(failing, succeeding));

        dispatcher.dispatch(UUID.randomUUID(), VerboseStreamPayload.builder().text("hello").build());

        assertEquals(2, calls.get());
    }

    @Test
    void dispatchNoopsForNullPayload() {
        AtomicInteger calls = new AtomicInteger();
        VerboseEventDispatcher dispatcher = new VerboseEventDispatcher(List.of((conversationId, payload) -> calls.incrementAndGet()));

        dispatcher.dispatch(UUID.randomUUID(), null);

        assertEquals(0, calls.get());
    }
}
