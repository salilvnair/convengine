package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "convengine.schema")
@Getter
@Setter
public class ConvEngineSchemaConfig {
    private String active;
}
