package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.cache.ConvEngineCacheAnalyzer;
import com.github.salilvnair.convengine.cache.StaticTableCachePreloader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/cache")
public class ConvEngineCacheController {

    private final StaticTableCachePreloader preloader;
    private final ConvEngineCacheAnalyzer cacheAnalyzer;
    private final CacheManager cacheManager;

    @PostMapping("/refresh")
    public ResponseEntity<String> refreshStaticCaches() {
        log.info("ConvEngine Admin: Received manual cache eviction payload. Purging and reconnecting to DB...");
        clearAllKnownCaches();

        // Immediately reload the tables into JVM
        preloader.preloadCaches();

        return ResponseEntity.ok("Successfully evicted and reloaded ConvEngine Static Database Configuration Caches.");
    }

    @GetMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeCaches(
            @RequestParam(name = "warmup", defaultValue = "true") boolean warmup
    ) {
        return ResponseEntity.ok(cacheAnalyzer.analyze(warmup));
    }

    private void clearAllKnownCaches() {
        if (cacheManager == null || cacheManager.getCacheNames() == null) {
            return;
        }
        for (String cacheName : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                continue;
            }
            try {
                cache.clear();
            } catch (Exception ex) {
                log.warn("ConvEngine Admin: Failed clearing cache '{}': {}", cacheName, ex.getMessage());
            }
        }
    }

}
