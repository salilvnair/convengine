package com.github.salilvnair.convengine.config.stream;

import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Conditional(ConvEngineStreamEnabledCondition.class)
public class ConvEngineStreamStartupValidator {

    private final ConvEngineTransportConfig transportConfig;

    @PostConstruct
    void validateTransportSelection() {
        boolean sseEnabled = transportConfig.getSse() != null && transportConfig.getSse().isEnabled();
        boolean stompEnabled = transportConfig.getStomp() != null && transportConfig.getStomp().isEnabled();
        if (!sseEnabled && !stompEnabled) {
            throw new IllegalStateException(
                    "ConvEngine streaming is enabled (@EnableConvEngine(stream=true)) but both transports are disabled. " +
                            "Enable at least one: convengine.transport.sse.enabled=true or convengine.transport.stomp.enabled=true"
            );
        }
    }
}
