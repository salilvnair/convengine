package com.github.salilvnair.convengine.config;

import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
public class ConvEngineAsyncConfiguration {
    // This allows ConvEngine to automatically register enabling async
    // when the consumer drops @EnableConvEngineAsyncConversation on their boot
    // class.
}
