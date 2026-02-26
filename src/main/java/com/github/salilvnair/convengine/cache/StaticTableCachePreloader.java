package com.github.salilvnair.convengine.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class StaticTableCachePreloader {

    private final StaticConfigurationCacheService staticCacheService;
    private final StaticScopeIntegrityValidator staticScopeIntegrityValidator;

    @EventListener(ApplicationReadyEvent.class)
    public void preloadCaches() {
        log.info("ConvEngine: Bootstrapping static configuration cache datasets into JVM memory.");

        staticCacheService.getAllConfigs();
        staticCacheService.getAllRules();
        staticCacheService.getAllPendingActions();
        staticCacheService.getAllIntents();
        staticCacheService.getAllIntentClassifiers();
        staticCacheService.getAllOutputSchemas();
        staticCacheService.getAllPromptTemplates();
        staticCacheService.getAllResponses();
        staticCacheService.getAllContainerConfigs();
        staticCacheService.getAllMcpTools();
        staticCacheService.getAllMcpDbTools();
        staticCacheService.getAllMcpPlanners();
        staticCacheService.getAllPolicies();
        staticScopeIntegrityValidator.validateOrThrow();

        log.info("ConvEngine: Static configuration preload complete.");
    }
}
