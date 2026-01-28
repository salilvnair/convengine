package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "convengine")
@Getter
@Setter
public class ConvEngineEntityConfig {
    private Map<String, String> tables = new HashMap<>();
}
