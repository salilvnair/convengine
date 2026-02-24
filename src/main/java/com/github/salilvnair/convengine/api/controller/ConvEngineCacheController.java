package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.cache.StaticTableCachePreloader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/cache")
public class ConvEngineCacheController {

    private final StaticTableCachePreloader preloader;

    @PostMapping("/refresh")
    @CacheEvict(value = {
            "ce_config",
            "ce_rule",
            "ce_pending_action",
            "ce_intent",
            "ce_intent_classifier",
            "ce_output_schema",
            "ce_prompt_template",
            "ce_response",
            "ce_container_config",
            "ce_mcp_tool",
            "ce_mcp_db_tool",
            "ce_policy"
    }, allEntries = true)
    public ResponseEntity<String> refreshStaticCaches() {
        log.info("ConvEngine Admin: Received manual cache eviction payload. Purging and reconnecting to DB...");

        // Immediately reload the tables into JVM
        preloader.preloadCaches();

        return ResponseEntity.ok("Successfully evicted and reloaded ConvEngine Static Database Configuration Caches.");
    }
}
