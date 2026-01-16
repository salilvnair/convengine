package com.github.salilvnair.convengine.prompt.config;

import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PromptConfig {
    @Bean
    public PromptTemplateRenderer promptTemplateRenderer() {
        return new PromptTemplateRenderer();
    }
}
