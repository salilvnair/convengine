package com.github.salilvnair.convengine.audit.dispatch;

import com.github.salilvnair.convengine.audit.AuditEventListener;
import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.config.feature.ConvEngineAsyncAuditDispatchMarker;
import com.github.salilvnair.convengine.entity.CeAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.salilvnair.convengine.support.TestConstants.BOOM;
import static com.github.salilvnair.convengine.support.TestConstants.EMPTY_JSON;
import static com.github.salilvnair.convengine.support.TestConstants.STEP_ENTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventDispatcherTest {

    @Mock
    private ObjectProvider<ConvEngineAsyncAuditDispatchMarker> markerProvider;

    private ConvEngineAuditConfig auditConfig;

    @BeforeEach
    void setUp() {
        auditConfig = new ConvEngineAuditConfig();
    }

    @Test
    void dispatchNotifiesRemainingListenersWhenOneFails() {
        when(markerProvider.getIfAvailable()).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();
        AuditEventListener failing = audit -> {
            calls.incrementAndGet();
            throw new RuntimeException(BOOM);
        };
        AuditEventListener succeeding = audit -> calls.incrementAndGet();
        AuditEventDispatcher dispatcher = new AuditEventDispatcher(List.of(failing, succeeding), auditConfig, markerProvider);

        dispatcher.dispatch(sampleAudit());

        assertEquals(2, calls.get());
    }

    @Test
    void dispatchNoopsForNullAudit() {
        AtomicInteger calls = new AtomicInteger();
        AuditEventDispatcher dispatcher = new AuditEventDispatcher(List.of(audit -> calls.incrementAndGet()), auditConfig, markerProvider);

        dispatcher.dispatch(null);

        assertEquals(0, calls.get());
    }

    @Test
    void droppedByBackpressureIsZeroWhenRunningSynchronously() {
        when(markerProvider.getIfAvailable()).thenReturn(null);
        AuditEventDispatcher dispatcher = new AuditEventDispatcher(List.of(audit -> { }), auditConfig, markerProvider);

        dispatcher.dispatch(sampleAudit());

        assertEquals(0L, dispatcher.droppedByBackpressureCount());
    }

    private CeAudit sampleAudit() {
        return CeAudit.builder()
                .conversationId(UUID.randomUUID())
                .stage(STEP_ENTER)
                .payloadJson(EMPTY_JSON)
                .build();
    }
}
