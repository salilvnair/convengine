package com.github.salilvnair.convengine.annotation;

import com.github.salilvnair.convengine.config.ConvEngineAsyncConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enable ConvEngine's background asynchronous thread execution mechanisms.
 * This internally triggers Spring Boot's generic
 * {@link org.springframework.scheduling.annotation.EnableAsync}.
 * ConvEngine uses this to offload slow I/O operations (like deep Relational DB
 * saves and external tool orchestration hooks)
 * to prevent blocking the direct response payload to the consumer.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ConvEngineAsyncConfiguration.class)
public @interface CeEnableAsyncConversation {
}
