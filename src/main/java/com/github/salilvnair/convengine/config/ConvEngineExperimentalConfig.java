package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "convengine.experimental")
@Getter
@Setter
public class ConvEngineExperimentalConfig {
    private boolean enabled = false;
}
