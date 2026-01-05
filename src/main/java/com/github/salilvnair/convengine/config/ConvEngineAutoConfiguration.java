package com.github.salilvnair.convengine.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@AutoConfigurationPackage(basePackages = "com.github.salilvnair.convengine")
@ComponentScan(basePackages = "com.github.salilvnair.convengine")
@EnableJpaRepositories(basePackages = "com.github.salilvnair.convengine.repo")
public class ConvEngineAutoConfiguration {
}