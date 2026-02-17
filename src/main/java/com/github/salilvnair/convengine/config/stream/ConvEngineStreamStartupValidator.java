package com.github.salilvnair.convengine.config.stream;

import com.github.salilvnair.convengine.config.ConvEngineTransportConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConvEngineStreamStartupValidator {

    private final ConvEngineTransportConfig transportConfig;
    private final ObjectProvider<ConvEngineStreamSettings> streamSettingsProvider;

    @PostConstruct
    void validateTransportSelection() {
        boolean streamEnabled = streamSettingsProvider
                .getIfAvailable(() -> new ConvEngineStreamSettings(true))
                .streamEnabled();
        if (!streamEnabled) {
            return;
        }

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
