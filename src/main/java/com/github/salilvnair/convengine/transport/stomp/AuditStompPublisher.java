package com.github.salilvnair.convengine.transport.stomp;

import com.github.salilvnair.convengine.api.dto.AuditStreamEventResponse;
import com.github.salilvnair.convengine.audit.AuditEventListener;
import com.github.salilvnair.convengine.audit.AuditPayloadMapper;
import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import com.github.salilvnair.convengine.entity.CeAudit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.github.salilvnair.convengine.config.stream.ConvEngineStreamEnabledCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Conditional(ConvEngineStreamEnabledCondition.class)
@ConditionalOnBean(SimpMessagingTemplate.class)
@ConditionalOnProperty(prefix = "convengine.transport.stomp", name = "enabled", havingValue = "true")
public class AuditStompPublisher implements AuditEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ConvEngineTransportConfig transportConfig;
    private final AuditPayloadMapper payloadMapper;

    @Override
    public void onAudit(CeAudit audit) {
        String destination = transportConfig.getStomp().getAuditDestinationBase() + "/" + audit.getConversationId();
        AuditStreamEventResponse event = new AuditStreamEventResponse(
                audit.getAuditId(),
                audit.getStage(),
                audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString(),
                payloadMapper.payloadAsMap(audit.getPayloadJson())
        );
        messagingTemplate.convertAndSend(destination, event);
    }
}
