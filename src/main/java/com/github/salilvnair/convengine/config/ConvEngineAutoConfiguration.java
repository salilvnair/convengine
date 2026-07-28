package com.github.salilvnair.convengine.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@AutoConfigurationPackage(basePackages = "com.github.salilvnair.convengine")
@ComponentScan(basePackages = "com.github.salilvnair.convengine")
@EntityScan(basePackages = {
        "com.github.salilvnair.convengine.entity",
        "com.github.salilvnair.convengine.builder.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.github.salilvnair.convengine.repo",
        "com.github.salilvnair.convengine.builder.repo"
})
public class ConvEngineAutoConfiguration {
}