package com.github.salilvnair.convengine.annotation;

import com.github.salilvnair.convengine.config.ConvEngineCacheConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enable ConvEngine's in-memory caching mechanisms, highly optimizing
 * Relational DB calls.
 * This internally triggers Spring Boot's generic
 * {@link org.springframework.cache.annotation.EnableCaching},
 * hooking into the default ConcurrentMapCacheManager or external providers
 * (Redis/Ehcache) if detected on the classpath.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ConvEngineCacheConfiguration.class)
public @interface CeEnableCaching {
}
