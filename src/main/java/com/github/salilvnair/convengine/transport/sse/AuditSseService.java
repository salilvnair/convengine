package com.github.salilvnair.convengine.transport.sse;

import com.github.salilvnair.convengine.api.dto.AuditStreamEventResponse;
import com.github.salilvnair.convengine.audit.AuditEventListener;
import com.github.salilvnair.convengine.audit.AuditPayloadMapper;
import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.transport.verbose.VerboseEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.github.salilvnair.convengine.config.stream.ConvEngineStreamEnabledCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Conditional(ConvEngineStreamEnabledCondition.class)
@ConditionalOnProperty(prefix = "convengine.transport.sse", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class AuditSseService implements AuditEventListener, VerboseEventListener {

    private final ConvEngineTransportConfig transportConfig;
    private final AuditPayloadMapper payloadMapper;
    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID conversationId) {
        long timeoutMs = transportConfig.getSse().getEmitterTimeoutMs();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(conversationId, emitter));
        emitter.onTimeout(() -> removeEmitter(conversationId, emitter));
        emitter.onError((error) -> removeEmitter(conversationId, emitter));
        sendConnected(conversationId, emitter);
        return emitter;
    }

    @Override
    public void onAudit(CeAudit audit) {
        Set<SseEmitter> conversationEmitters = emitters.get(audit.getConversationId());
        if (conversationEmitters == null || conversationEmitters.isEmpty()) {
            return;
        }

        Map<String, Object> payload = payloadMapper.payloadAsMap(audit.getPayloadJson());
        AuditStreamEventResponse event = new AuditStreamEventResponse(
                "AUDIT",
                audit.getAuditId(),
                audit.getStage(),
                audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString(),
                payload,
                null);

        for (SseEmitter emitter : conversationEmitters.toArray(new SseEmitter[0])) {
            try {
                emitter.send(SseEmitter.event().name(audit.getStage()).data(event));
            } catch (IOException ex) {
                removeEmitter(audit.getConversationId(), emitter);
            }
        }
    }

    @Override
    public void onVerbose(UUID conversationId, VerboseStreamPayload verbosePayload) {
        Set<SseEmitter> conversationEmitters = emitters.get(conversationId);
        if (conversationEmitters == null || conversationEmitters.isEmpty() || verbosePayload == null) {
            return;
        }
        AuditStreamEventResponse event = new AuditStreamEventResponse(
                "VERBOSE",
                null,
                "VERBOSE",
                OffsetDateTime.now().toString(),
                Map.of("verbose", verbosePayload),
                verbosePayload);
        for (SseEmitter emitter : conversationEmitters.toArray(new SseEmitter[0])) {
            try {
                emitter.send(SseEmitter.event().name("VERBOSE").data(event));
            } catch (IOException ex) {
                removeEmitter(conversationId, emitter);
            }
        }
    }

    private void sendConnected(UUID conversationId, SseEmitter emitter) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("CONNECTED")
                            .data(Map.of(
                                    "conversationId", String.valueOf(conversationId),
                                    "transport", "SSE")));
        } catch (IOException e) {
            removeEmitter(conversationId, emitter);
            log.debug("SSE connected event failed convId={} msg={}", conversationId, e.getMessage());
        }
    }

    private void removeEmitter(UUID conversationId, SseEmitter emitter) {
        Set<SseEmitter> conversationEmitters = emitters.get(conversationId);
        if (conversationEmitters == null) {
            return;
        }
        conversationEmitters.remove(emitter);
        if (conversationEmitters.isEmpty()) {
            emitters.remove(conversationId);
        }
    }
}
