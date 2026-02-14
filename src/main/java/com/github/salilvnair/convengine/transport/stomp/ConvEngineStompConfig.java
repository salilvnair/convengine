package com.github.salilvnair.convengine.transport.stomp;

import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import com.github.salilvnair.convengine.config.feature.ConvEngineStompBrokerRelayMarker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import com.github.salilvnair.convengine.config.stream.ConvEngineStreamEnabledCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Conditional(ConvEngineStreamEnabledCondition.class)
@ConditionalOnClass(WebSocketMessageBrokerConfigurer.class)
@ConditionalOnProperty(prefix = "convengine.transport.stomp", name = "enabled", havingValue = "true")
public class ConvEngineStompConfig implements WebSocketMessageBrokerConfigurer {

    private final ConvEngineTransportConfig transportConfig;
    private final ObjectProvider<ConvEngineStompBrokerRelayMarker> relayMarkerProvider;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String endpoint = transportConfig.getStomp().getEndpoint();
        String allowedPattern = transportConfig.getStomp().getAllowedOriginPattern();
        if (transportConfig.getStomp().isSockJs()) {
            registry.addEndpoint(endpoint)
                    .setAllowedOriginPatterns(allowedPattern)
                    .withSockJS();
            return;
        }
        registry.addEndpoint(endpoint)
                .setAllowedOriginPatterns(allowedPattern);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(transportConfig.getStomp().getAppDestinationPrefix());
        if (useBrokerRelay()) {
            ConvEngineTransportConfig.Broker relay = transportConfig.getStomp().getBroker();
            List<String> relayPrefixes = relay.getRelayDestinationPrefixes();
            if (relayPrefixes == null || relayPrefixes.isEmpty()) {
                relayPrefixes = List.of(transportConfig.getStomp().getTopicPrefix());
            }
            String[] prefixes = relayPrefixes.toArray(String[]::new);
            StompBrokerRelayRegistration registration = registry.enableStompBrokerRelay(prefixes);
            registration.setRelayHost(relay.getRelayHost());
            registration.setRelayPort(relay.getRelayPort());
            if (!relay.getClientLogin().isBlank()) {
                registration.setClientLogin(relay.getClientLogin());
            }
            if (!relay.getClientPasscode().isBlank()) {
                registration.setClientPasscode(relay.getClientPasscode());
            }
            if (!relay.getSystemLogin().isBlank()) {
                registration.setSystemLogin(relay.getSystemLogin());
            }
            if (!relay.getSystemPasscode().isBlank()) {
                registration.setSystemPasscode(relay.getSystemPasscode());
            }
            if (!relay.getVirtualHost().isBlank()) {
                registration.setVirtualHost(relay.getVirtualHost());
            }
            registration.setSystemHeartbeatSendInterval(Math.max(0, relay.getSystemHeartbeatSendIntervalMs()));
            registration.setSystemHeartbeatReceiveInterval(Math.max(0, relay.getSystemHeartbeatReceiveIntervalMs()));
            return;
        }
        registry.enableSimpleBroker(transportConfig.getStomp().getTopicPrefix());
    }

    private boolean useBrokerRelay() {
        return relayMarkerProvider.getIfAvailable() != null
                || ConvEngineTransportConfig.Mode.RELAY == transportConfig.getStomp().getBroker().getMode();
    }
}
