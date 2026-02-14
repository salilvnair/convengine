package com.github.salilvnair.convengine.transport.stomp;

import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@ConditionalOnClass(WebSocketMessageBrokerConfigurer.class)
@ConditionalOnProperty(prefix = "convengine.transport.stomp", name = "enabled", havingValue = "true")
public class ConvEngineStompConfig implements WebSocketMessageBrokerConfigurer {

    private final ConvEngineTransportConfig transportConfig;

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
        registry.enableSimpleBroker(transportConfig.getStomp().getTopicPrefix());
    }
}
