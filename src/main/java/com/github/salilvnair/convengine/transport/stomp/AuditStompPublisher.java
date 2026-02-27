package com.github.salilvnair.convengine.transport.stomp;

import com.github.salilvnair.convengine.api.dto.AuditStreamEventResponse;
import com.github.salilvnair.convengine.audit.AuditEventListener;
import com.github.salilvnair.convengine.audit.AuditPayloadMapper;
import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.transport.verbose.VerboseEventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.github.salilvnair.convengine.config.stream.ConvEngineStreamEnabledCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Conditional(ConvEngineStreamEnabledCondition.class)
@ConditionalOnBean(SimpMessagingTemplate.class)
@ConditionalOnProperty(prefix = "convengine.transport.stomp", name = "enabled", havingValue = "true")
public class AuditStompPublisher implements AuditEventListener, VerboseEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ConvEngineTransportConfig transportConfig;
    private final AuditPayloadMapper payloadMapper;

    @Override
    public void onAudit(CeAudit audit) {
        String destination = transportConfig.getStomp().getAuditDestinationBase() + "/" + audit.getConversationId();
        Map<String, Object> payload = payloadMapper.payloadAsMap(audit.getPayloadJson());
        AuditStreamEventResponse event = new AuditStreamEventResponse(
                "AUDIT",
                audit.getAuditId(),
                audit.getStage(),
                audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString(),
                payload,
                null);
        messagingTemplate.convertAndSend(destination, event);
    }

    @Override
    public void onVerbose(UUID conversationId, VerboseStreamPayload verbosePayload) {
        if (conversationId == null || verbosePayload == null) {
            return;
        }
        String destination = transportConfig.getStomp().getAuditDestinationBase() + "/" + conversationId;
        AuditStreamEventResponse event = new AuditStreamEventResponse(
                "VERBOSE",
                null,
                "VERBOSE",
                OffsetDateTime.now().toString(),
                Map.of("verbose", verbosePayload),
                verbosePayload);
        messagingTemplate.convertAndSend(destination, event);
    }
}
