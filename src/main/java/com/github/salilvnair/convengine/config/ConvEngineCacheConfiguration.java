package com.github.salilvnair.convengine.config;

import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
public class ConvEngineCacheConfiguration {
    // This allows ConvEngine to automatically register enabling caching
    // when the consumer drops @EnableConvEngineCaching on their boot class.
}
